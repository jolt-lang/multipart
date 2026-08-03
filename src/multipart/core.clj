(ns multipart.core
  "High-level form parsing API for ring-style request maps, plus the full
  public API surface of the library.

  The library is split by function:

  - `multipart.errors`  — error constructors and classification predicates
  - `multipart.bytes`   — low-level byte/ASCII/charset helpers
  - `multipart.headers` — RFC 7230/7578 header value parsing and quoting
  - `multipart.parser`  — incremental SansIO parser (make-parser/parse-chunk/...)
  - this namespace      — parse-multipart/parse-form-data/is-form-request?,
                          re-exporting the public API of the namespaces above
                          so a single require of multipart.core suffices"
  (:require [clojure.string :as str]
            [multipart.bytes :as bytes]
            [multipart.headers :as headers]
            [multipart.parser :as parser]
            [multipart.errors :as errors]))

;; Re-exports: callers can require just multipart.core, as before the
;; refactor into sub-namespaces.
(def make-parser parser/make-parser)
(def close-parser parser/close-parser)
(def parse-chunk parser/parse-chunk)
(def parse-stream parser/parse-stream)
(def parse-content-disposition headers/parse-content-disposition)
(def parse-options-header headers/parse-options-header)
(def header-quote headers/header-quote)
(def header-unquote headers/header-unquote)
(def content-disposition-quote headers/content-disposition-quote)
(def content-disposition-unquote headers/content-disposition-unquote)
(def multipart-error? errors/multipart-error?)
(def parser-error? errors/parser-error?)
(def strict-parser-error? errors/strict-parser-error?)
(def parser-limit-reached? errors/parser-limit-reached?)
(def parser-state-error? errors/parser-state-error?)

;;; ---------------------------------------------------------------------------
;;; High-level API
;;; ---------------------------------------------------------------------------

(defn- assoc-conj [m k v]
  (assoc m k (if-let [cur (get m k)]
               (if (vector? cur) (conj cur v) [cur v])
               v)))

(defn- reduce-part-events [events memory-limit]
  (loop [events (seq events)
         current nil
         parts []
         total 0]
    (if events
      (let [event (first events)]
        (case (first event)
          :segment (recur (next events) (assoc (second event) :bytes (byte-array 0)) parts total)
          :body (recur (next events)
                       (update current :bytes bytes/ba-concat (second event))
                       parts total)
          :end (do
                 (when (and memory-limit (> (+ total (alength (:bytes current))) memory-limit))
                   (throw (errors/parser-limit-reached "Memory limit reached")))
                 (recur (next events) nil
                        (conj parts current)
                        (+ total (alength (:bytes current)))))))
      parts)))

(defn parse-multipart
  "Parse a multipart/form-data body into a vector of part maps, each with
  :name, :filename, :content-type, :charset, :headerlist and :bytes keys.

  body is either a byte array holding the complete body, or a read-fn of
  a size argument returning byte arrays (nil at EOF) for streaming input.
  Options are the same as for `make-parser`, plus :memory-limit (nil =
  unlimited) capping the total payload bytes across all parts."
  ([boundary body] (parse-multipart boundary body {}))
  ([boundary body {:keys [memory-limit] :as opts}]
   (let [p (apply parser/make-parser boundary (mapcat identity opts))]
     (if (fn? body)
       (reduce-part-events (parser/parse-stream p body) memory-limit)
       (let [[p events] (parser/parse-chunk p body)]
         (parser/close-parser p)
         (reduce-part-events events memory-limit))))))

(defn is-form-request?
  "True if content-type represents a form request that can be parsed with
  `parse-form-data`."
  [content-type]
  (contains? #{"multipart/form-data" "application/x-www-form-urlencoded"
               "application/x-url-encoded"}
             (str/lower-case
               (str/trim (first (str/split (or content-type "") #";" 2))))))

(defn- percent-decode
  "Decode percent-encoded bytes to a string. '+' decodes to space; invalid
  escape sequences are passed through literally."
  [^bytes ba charset]
  (let [n (alength ba)
        hex-val (fn [b]
                  (let [c (int b)]
                    (cond (<= 48 c 57) (- c 48)
                          (<= 65 c 70) (- c 55)
                          (<= 97 c 102) (- c 87)
                          :else -1)))]
    (loop [i 0 out []]
      (if (>= i n)
        (bytes/ba->str (byte-array out) charset)
        (let [c (int (aget ba i))]
          (cond
            (= c 43)
            (recur (inc i) (conj out 32))

            (and (= c 37) (< (+ i 2) n)
                 (>= (hex-val (aget ba (inc i))) 0)
                 (>= (hex-val (aget ba (+ i 2))) 0))
            (recur (+ i 3)
                   (conj out (+ (* 16 (hex-val (aget ba (inc i))))
                                (hex-val (aget ba (+ i 2))))))

            :else
            (recur (inc i) (conj out c))))))))

(defn- split-on-first [s sep]
  (let [i (str/index-of s sep)]
    (if (nil? i) [s nil] [(subs s 0 i) (subs s (inc i))])))

(defn- parse-urlencoded [^bytes body charset opts]
  (let [mem-limit (or (:memory-limit opts) (* 1024 64 128))
        part-limit (or (:part-limit opts) 128)]
    (when (> (alength body) mem-limit)
      (throw (errors/parser-limit-reached "Memory limit exceeded")))
    (let [fields (str/split (bytes/ba->str body charset) #"&" (inc part-limit))]
      (when (> (count fields) part-limit)
        (throw (errors/parser-limit-reached "Memory limit exceeded")))
      (reduce
        (fn [params field]
          (let [[name value] (split-on-first field "=")]
            (if (str/blank? name)
              params
              (assoc-conj params
                          (percent-decode (bytes/str->ba name charset) charset)
                          (percent-decode (bytes/str->ba (or value "") charset) charset)))))
        {} fields))))

(defn parse-form-data
  "Parse form data from a ring-style request map (:headers with
  \"content-type\", :body with a byte array or read-fn) into
  {:params {...} :files {...}}.

  Text fields are decoded to strings; file uploads are returned as part
  maps (see `parse-multipart`) keyed by field name. Multiple values for
  the same name are collected into vectors.

  Options:
    :charset       default charset for headers and text fields (default \"utf-8\")
    :strict        raise on unusual or unsupported input
    :ignore-errors if true, suppress all exceptions and return empty
                   results; if false, never suppress; nil (default)
                   suppresses in non-strict mode
    plus the `parse-multipart` options."
  ([request] (parse-form-data request {}))
  ([request {:keys [charset strict ignore-errors] :or {charset "utf-8"} :as opts}]
   (let [content-type (or (get-in request [:headers "content-type"])
                          (get request :content-type))]
     (try
       (if (nil? content-type)
         (if strict
           (throw (errors/strict-parser-error "Missing Content-Type header"))
           {:params {} :files {}})
         (let [[ctype options] (headers/parse-options-header content-type)
               charset (or (get options "charset") charset)]
           (when-not (bytes/is-valid-charset? charset)
             (throw (errors/parser-error (str "Invalid charset: " (pr-str charset)))))
           (cond
             (= ctype "multipart/form-data")
             (let [boundary (get options "boundary")]
               (when (str/blank? boundary)
                 (throw (errors/parser-error "Missing boundary for multipart/form-data")))
               (reduce
                 (fn [acc part]
                   (if (:filename part)
                     (update acc :files assoc-conj (:name part) part)
                     (update acc :params assoc-conj (:name part)
                             (bytes/ba->str (:bytes part) (or (:charset part) charset)))))
                 {:params {} :files {}}
                 (parse-multipart boundary (:body request) (dissoc opts :charset))))

             (contains? #{"application/x-www-form-urlencoded" "application/x-url-encoded"} ctype)
             {:params (parse-urlencoded (:body request) charset opts) :files {}}

             strict
             (throw (errors/strict-parser-error "Unsupported Content-Type"))

             :else
             {:params {} :files {}})))
       (catch Throwable e
         (if (errors/multipart-error? e)
           (let [ignore (if (nil? ignore-errors) (not strict) ignore-errors)]
             (if ignore {:params {} :files {}} (throw e)))
           (throw e)))))))
