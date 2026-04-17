(ns datalaga.memory.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalaga.memory.store :as store]
            [datalaga.test-support :as test-support]
            [datalaga.util :as util]))

(defn- seed-minimal!
  [conn]
  (store/transact-entities!
   conn
   [{:entity/id "p1" :entity/type :project :entity/name "P1"}
    {:entity/id "p2" :entity/type :project :entity/name "P2"}
    {:entity/id "f1" :entity/type :file :entity/project "p1" :entity/path "src/a.clj" :entity/created-at "2026-03-01T10:00:00Z"}
    {:entity/id "f2" :entity/type :file :entity/project "p1" :entity/path "src/b.clj" :entity/created-at "2026-03-02T10:00:00Z"}
    {:entity/id "f3" :entity/type :file :entity/project "p2" :entity/path "src/c.clj" :entity/created-at "2026-03-03T10:00:00Z"}
    {:entity/id "s1" :entity/type :symbol :entity/project "p1" :entity/name "n1" :symbol/file "f1"}
    {:entity/id "s2" :entity/type :symbol :entity/project "p2" :entity/name "n2" :symbol/file "f3"}
    {:entity/id "t1" :entity/type :task :entity/project "p1" :entity/name "task1" :task/touched-files ["f1" "f2"]}
    {:entity/id "t2" :entity/type :task :entity/project "p2" :entity/name "task2" :task/touched-files ["f3"]}]))

(deftest project-scoped-helper-queries
  (doseq [backend test-support/test-backends]
    (testing (name backend)
      (test-support/with-temp-conn
       backend
       (fn [conn _db-path]
         (seed-minimal! conn)
         (let [db-value (store/db conn)
               p1-eid (store/require-project-eid db-value "p1")]
           (testing "project-entity-ids-by-type only returns matching project members"
             (is (= #{"f1" "f2"}
                    (set (store/project-entity-ids-by-type db-value p1-eid :file)))))
           (testing "project-entity-ids-for-eid returns all non-project entities in the project"
             (is (= #{"f1" "f2" "s1" "t1"}
                    (set (store/project-entity-ids-for-eid db-value p1-eid)))))
           (testing "project-entity-ids-by-type-and-attr-value filters by attr value"
             (is (= #{"f1"}
                    (set (store/project-entity-ids-by-type-and-attr-value db-value p1-eid :file :entity/path "src/a.clj")))))
           (testing "project-max-instant returns the latest timestamp in scope"
             (is (= "2026-03-02T10:00:00Z"
                    (some-> (store/project-max-instant db-value p1-eid :entity/created-at)
                            util/json-friendly)))
             (is (nil? (store/project-max-instant db-value p1-eid :session/started-at))))
           (testing "project-ref-edge-pairs returns ref edges rooted in the project"
             (is (= #{["t1" "f1"] ["t1" "f2"]}
                    (set (store/project-ref-edge-pairs db-value p1-eid :task/touched-files)))))))))))

(deftest transact-entities-batch-ref-and-atomicity
  (doseq [backend test-support/test-backends]
    (testing (name backend)
      (test-support/with-temp-conn
       backend
       (fn [conn _db-path]
         (testing "in-batch refs resolve when both new entities are in one transaction"
           (store/transact-entities!
            conn
            [{:entity/id "proj" :entity/type :project}
             {:entity/id "sym-new"
              :entity/type :symbol
              :entity/project "proj"
              :entity/name "sym-new"}
             {:entity/id "task-new"
              :entity/type :task
              :entity/project "proj"
              :entity/name "task-new"
              :task/related-symbols ["sym-new"]}])
           (let [db-value (store/db conn)
                 task (store/pull-entity-by-id db-value "task-new")]
             (is (= ["sym-new"] (mapv :entity/id (:task/related-symbols task))))))
         (testing "invalid ref aborts the whole transaction batch"
           (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"Referenced entity does not exist"
                (store/transact-entities!
                 conn
                 [{:entity/id "task-bad"
                   :entity/type :task
                   :entity/project "proj"
                   :task/related-symbols ["missing-sym"]}
                  {:entity/id "sym-should-not-commit"
                   :entity/type :symbol
                   :entity/project "proj"}])))
           (let [db-value (store/db conn)]
             (is (nil? (store/pull-entity-by-id db-value "task-bad")))
             (is (nil? (store/pull-entity-by-id db-value "sym-should-not-commit"))))))))))
