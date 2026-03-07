(ns datalaga.mcp.server-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datalaga.mcp.server :as mcp-server]
            [datalaga.memory.store :as store]))

(defn- delete-tree!
  [path]
  (let [root (io/file path)]
    (when (.exists root)
      (doseq [f (reverse (file-seq root))]
        (io/delete-file f true)))))

(defn- with-temp-conn
  [f]
  (let [db-path (.getAbsolutePath (.toFile (java.nio.file.Files/createTempDirectory "datalaga-mcp-test-db" (make-array java.nio.file.attribute.FileAttribute 0))))
        conn (store/open-conn db-path)]
    (try
      (f conn)
      (finally
        (store/close! conn)
        (delete-tree! db-path)))))

(deftest memory-query-applies-pagination-guards
  (with-temp-conn
    (fn [conn]
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
        (is (pos? (:total_count result)))))))

(deftest memory-query-preserves-scalar-results
  (with-temp-conn
    (fn [conn]
      (store/transact-entities!
       conn
       [{:entity/id "p1" :entity/type :project}
        {:entity/id "n1" :entity/type :note :entity/project "p1" :entity/body "a"}])
      (let [result (#'mcp-server/memory-query!
                    conn
                    {:query_edn "[:find (count ?e) . :where [?e :entity/id _]]"})]
        (is (number? (:results result)))
        (is (nil? (:total_count result)))
        (is (nil? (:truncated result)))))))
