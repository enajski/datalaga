(ns datalaga.test-runner
  (:require [clojure.test :as t]))

(defn -main
  [& _]
  (require 'datalaga.memory.store-test)
  (require 'datalaga.memory.queries-test)
  (let [{:keys [fail error]} (t/run-tests 'datalaga.memory.store-test
                                          'datalaga.memory.queries-test)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
