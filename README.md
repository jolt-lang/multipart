# multipart

An RFC 7578 `multipart/form-data` parser for Clojure and the [Jolt](https://github.com/squint-cljs/jolt) (squint) dialect, ported from [defnull/multipart](https://github.com/defnull/multipart). It is designed to be used by [ring-core](https://github.com/ring-clojure/ring) to parse form uploads.

Both the incremental push parser and the high-level `parse-form-data` API are implemented, so you can either stream a request body chunk by chunk or parse it all at once.

## Adding the dependency

Add the git dependency to `deps.edn`:

```clojure
{:deps {multipart/multipart
        {:git/url "https://github.com/jolt-lang/multipart"
         :git/sha "..."}}}
```

Replace the SHA with the current commit on the repository (or the `:git/tag` of a release). For local development, a `:local/root` path works too:

```clojure
{:deps {multipart/multipart {:local/root "../multipart"}}}
```

## Quick start

```clojure
(require '[multipart.core :as multipart])

;; ring-style request map; :body is a byte array or a read-fn
(def request
  {:headers {"content-type" "multipart/form-data; boundary=boundary123"}
   :body    (byte-array (.getBytes (str "--boundary123\r\n"
                                        "Content-Disposition: form-data; name=\"user\"\r\n\r\n"
                                        "alice\r\n"
                                        "--boundary123\r\n"
                                        "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                                        "Content-Type: text/plain\r\n\r\n"
                                        "file contents\r\n"
                                        "--boundary123--")
                                   "UTF-8"))})

(multipart/parse-form-data request)
;; => {:params {"user" "alice"}
;;     :files  {"file" {:name "file" :filename "a.txt"
;;                      :content-type "text/plain" :bytes #object["[B" ...]}}}
```

Text fields come back decoded in `:params`; file uploads in `:files`, each a map with `:name`, `:filename`, `:content-type`, `:charset`, `:headerlist` and `:bytes`. Multiple values for the same field name are collected into vectors. It also handles `application/x-www-form-urlencoded` bodies.

`multipart/is-form-request?` tells you whether a `Content-Type` value can be parsed this way:

```clojure
(multipart/is-form-request? "multipart/form-data; boundary=x") ; => true
```

## API

### `parse-form-data`

`(parse-form-data request & [opts])` → `{:params {...} :files {...}}`

Parses a ring-style request map (`:headers` with `"content-type"`, `:body` with a byte array or read-fn). Options:

- `:charset` — default charset for headers and text fields (default `"utf-8"`)
- `:strict` — raise on unusual or unsupported input
- `:ignore-errors` — `true` suppresses all exceptions and returns empty results; `false` never suppresses; `nil` (default) suppresses in non-strict mode
- all `parse-multipart` options below

### `parse-multipart`

`(parse-multipart boundary body & [opts])` → vector of part maps

Parses a complete multipart body. `boundary` is the boundary string; `body` is either a byte array or a read-fn of a size argument returning byte arrays (`nil` at EOF). Each part map has `:name`, `:filename`, `:content-type`, `:charset`, `:headerlist` and `:bytes`.

### Streaming parser

`(make-parser boundary & opts)` → parser map, then feed it to `parse-chunk`:

```clojure
(let [parser (multipart/make-parser "boundary123")]
  (loop [parser parser, chunk (read-chunk), events []]
    (if (empty? chunk)
      (do (multipart/close-parser parser) events)
      (let [[parser evs] (multipart/parse-chunk parser chunk)]
        (recur parser (read-chunk) (into events evs))))))
```

`parse-chunk` returns `[parser events]` where events are `[:segment segment-map]`, `[:body byte-array]` and `[:end]` — one `:segment` per part with its parsed headers, zero or more `:body` events with payload data, and one `:end`. An empty chunk signals end of input and closes the parser; an incomplete stream raises a `ParserError`.

`parse-stream` is the convenience wrapper: `(parse-stream parser read-fn & [chunk-size])` returns a lazy seq of the same events, closing the parser at EOF.

`make-parser` options:

- `:content-length` — expected input size in bytes, `-1` (default) if unknown
- `:max-header-size` — max length of a single header line (default 4224)
- `:max-header-count` — max headers per segment (default 8)
- `:max-segment-size` — max size of a single segment body (`nil` = unlimited)
- `:max-segment-count` — max number of segments (`nil` = unlimited)
- `:header-charset` — charset for header names and values (default `"utf-8"`)
- `:strict` — enable additional format and sanity checks

### Header utilities

- `(parse-content-disposition header)` → `[disposition name filename]`
- `(parse-options-header header)` → `[primary-value options-map]` — for `Content-Type`-style headers; pass `content-disposition-unquote` as the unquote fn for `Content-Disposition`
- `(header-quote s)` / `(header-unquote s)` — quote/unquote RFC 7230 header values
- `(content-disposition-quote s)` / `(content-disposition-unquote s)` — quote/unquote the way browsers do (HTML5 limited percent-encoding), for field names and filenames

### Errors

Parser failures throw `ex-info` with a `:type` key in `ex-data`; test for them with the predicates `multipart-error?`, `parser-error?`, `strict-parser-error?`, `parser-limit-reached?` and `parser-state-error?`.

## Notes

- The source is split into `multipart.errors` (error types), `multipart.bytes` (byte/ASCII helpers), `multipart.headers` (header parsing), `multipart.parser` (the incremental parser) and `multipart.core` (high-level API). `multipart.core` re-exports the public functions of the other namespaces, so requiring just `multipart.core` gives you the whole API.
- Payloads are always buffered in memory — there is no temp-file spooling.
- The code targets Jolt-compatible Clojure: byte primitives and regex functions are used in their Jolt-safe forms, so the namespace runs under both the JVM and Jolt.

## Running the tests

```
clojure -M:test
```

## License

MIT, same as the upstream Python library.
