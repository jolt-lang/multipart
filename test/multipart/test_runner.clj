(ns multipart.test-runner
  (:require [clojure.test :as t]
            [multipart.core-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'multipart.core-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
