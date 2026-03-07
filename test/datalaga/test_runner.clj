(ns datalaga.test-runner
  (:require [clojure.test :as t]))

(defn -main
  [& _]
  (require 'datalaga.memory.store-test)
  (let [{:keys [fail error]} (t/run-tests 'datalaga.memory.store-test)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
