(ns datalaga.mcp.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalaga.mcp.server :as mcp-server]
            [datalaga.memory.store :as store]
            [datalaga.test-support :as test-support]))

(deftest memory-query-applies-pagination-guards
  (doseq [backend test-support/test-backends]
    (testing (name backend)
      (test-support/with-temp-conn
       backend
       (fn [conn _db-path]
         (store/transact-entities!
          conn
          [{:entity/id "p1" :entity/type :project}
           {:entity/id "n1" :entity/type :note :entity/project "p1" :entity/body "a"}
           {:entity/id "n2" :entity/type :note :entity/project "p1" :entity/body "b"}
           {:entity/id "n3" :entity/type :note :entity/project "p1" :entity/body "c"}])
         (let [result (#'mcp-server/memory-query!
                       conn
                       {:query_edn "[:find [?id ...] :where [?e :entity/id ?id]]"
                        :max_results 1
                        :offset 1
                        :timeout_ms 100})]
           (is (= 1 (count (:results result))))
           (is (= 1 (:max_results result)))
           (is (= 1 (:offset result)))
           (is (= 100 (:timeout_ms result)))
           (is (true? (:truncated result)))
           (is (pos? (:total_count result)))))))))

(deftest memory-query-preserves-scalar-results
  (doseq [backend test-support/test-backends]
    (testing (name backend)
      (test-support/with-temp-conn
       backend
       (fn [conn _db-path]
         (store/transact-entities!
          conn
          [{:entity/id "p1" :entity/type :project}
           {:entity/id "n1" :entity/type :note :entity/project "p1" :entity/body "a"}])
         (let [result (#'mcp-server/memory-query!
                       conn
                       {:query_edn "[:find (count ?e) . :where [?e :entity/id _]]"})]
           (is (number? (:results result)))
           (is (nil? (:total_count result)))
           (is (nil? (:truncated result)))))))))
