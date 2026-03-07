(ns datalaga.memory.queries-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datalaga.memory.queries :as queries]
            [datalaga.memory.store :as store]))

(defn- delete-tree!
  [path]
  (let [root (io/file path)]
    (when (.exists root)
      (doseq [f (reverse (file-seq root))]
        (io/delete-file f true)))))

(defn- with-temp-conn
  [f]
  (let [db-path (.getAbsolutePath (.toFile (java.nio.file.Files/createTempDirectory "datalaga-query-test-db" (make-array java.nio.file.attribute.FileAttribute 0))))
        conn (store/open-conn db-path)]
    (try
      (f conn)
      (finally
        (store/close! conn)
        (delete-tree! db-path)))))

(defn- seed-query-fixture!
  [conn]
  (store/transact-entities!
   conn
   [{:entity/id "p1" :entity/type :project :entity/name "P1"}
    {:entity/id "p2" :entity/type :project :entity/name "P2"}
    {:entity/id "f1" :entity/type :file :entity/project "p1" :entity/path "src/a.clj" :entity/body "file a"}
    {:entity/id "f2" :entity/type :file :entity/project "p2" :entity/path "src/b.clj" :entity/body "file b"}
    {:entity/id "t1"
     :entity/type :task
     :entity/project "p1"
     :entity/name "Task 1"
     :entity/body "task one"
     :entity/created-at "2026-03-02T10:00:00Z"
     :task/touched-files ["f1"]}
    {:entity/id "t2"
     :entity/type :task
     :entity/project "p2"
     :entity/name "Task 2"
     :entity/body "task two"
     :entity/created-at "2026-03-02T10:00:00Z"
     :task/touched-files ["f2"]}
    {:entity/id "d-old"
     :entity/type :decision
     :entity/project "p1"
     :entity/name "Old decision"
     :entity/body "decide old"
     :entity/created-at "2026-03-01T10:00:00Z"
     :decision/related-files ["f1"]}
    {:entity/id "d-new"
     :entity/type :decision
     :entity/project "p1"
     :entity/name "New decision"
     :entity/body "decide new"
     :entity/created-at "2026-03-03T10:00:00Z"
     :decision/related-files ["f1"]}
    {:entity/id "d-other"
     :entity/type :decision
     :entity/project "p2"
     :entity/name "Other decision"
     :entity/body "decide other"
     :entity/created-at "2026-03-01T10:00:00Z"
     :decision/related-files ["f2"]}
    {:entity/id "run1"
     :entity/type :tool-run
     :entity/project "p1"
     :entity/task "t1"
     :entity/name "run"
     :entity/body "run body"
     :entity/created-at "2026-03-02T10:01:00Z"
     :tool-run/command "clj -M:test"}
    {:entity/id "err1"
     :entity/type :error
     :entity/project "p1"
     :entity/task "t1"
     :entity/name "error"
     :entity/body "error body"
     :entity/created-at "2026-03-02T10:02:00Z"
     :entity/status :open
     :error/details "boom"}
    {:entity/id "note-ref"
     :entity/type :note
     :entity/project "p1"
     :entity/name "note ref"
     :entity/body "this note references task"
     :entity/created-at "2026-03-02T10:03:00Z"
     :note/refers-to ["t1"]}
    {:entity/id "event-ref"
     :entity/type :event
     :entity/project "p1"
     :entity/name "event ref"
     :entity/body "subject mentions task"
     :entity/created-at "2026-03-02T10:04:00Z"
     :event/at "2026-03-02T10:04:00Z"
     :event/subjects ["t1"]}
    {:entity/id "note-hit-p1"
     :entity/type :note
     :entity/project "p1"
     :entity/name "p1 note"
     :entity/body "login timeout troubleshooting"
     :entity/created-at "2026-03-02T12:00:00Z"
     :note/content "login timeout troubleshooting"}
    {:entity/id "note-hit-p2"
     :entity/type :note
     :entity/project "p2"
     :entity/name "p2 note"
     :entity/body "login timeout troubleshooting"
     :entity/created-at "2026-03-02T12:00:00Z"
     :note/content "login timeout troubleshooting"}]))

(deftest prior-decisions-for-task-filters-by-project-and-time
  (with-temp-conn
    (fn [conn]
      (seed-query-fixture! conn)
      (let [result (queries/prior-decisions-for-task conn "t1")
            ids (mapv :entity/id result)]
        (is (= ["d-old"] ids))))))

(deftest get-task-timeline-includes-direct-and-reference-links
  (with-temp-conn
    (fn [conn]
      (seed-query-fixture! conn)
      (let [timeline (queries/get-task-timeline conn {:task-id "t1"})
            ids (set (map :entity/id (:timeline timeline)))]
        (is (= "t1" (get-in timeline [:task :entity/id])))
        (is (contains? ids "t1"))
        (is (contains? ids "run1"))
        (is (contains? ids "err1"))
        (is (contains? ids "note-ref"))
        (is (contains? ids "event-ref"))
        (is (not (contains? ids "t2")))))))

(deftest search-notes-respects-project-scope
  (with-temp-conn
    (fn [conn]
      (seed-query-fixture! conn)
      (let [hits-p1 (queries/search-notes conn {:project-id "p1"
                                                :query "login timeout"
                                                :limit 10})
            hit-ids-p1 (set (map :entity/id (:matches hits-p1)))]
        (is (contains? hit-ids-p1 "note-hit-p1"))
        (is (not (contains? hit-ids-p1 "note-hit-p2"))))
      (let [hits-cross (queries/search-notes conn {:project-ids ["p1" "p2"]
                                                   :query "login timeout"
                                                   :limit 10})
            hit-ids-cross (set (map :entity/id (:matches hits-cross)))]
        (is (contains? hit-ids-cross "note-hit-p1"))
        (is (contains? hit-ids-cross "note-hit-p2")))
      (let [paged (queries/search-notes conn {:project-ids ["p1" "p2"]
                                              :query "login timeout"
                                              :limit 1
                                              :offset 1})]
        (is (= 1 (count (:matches paged))))
        (is (= 1 (:limit paged)))
        (is (= 1 (:offset paged))))
      (let [clamped (queries/search-notes conn {:project-ids ["p1" "p2"]
                                                :query "login timeout"
                                                :limit 999
                                                :offset -10})]
        (is (= 50 (:limit clamped)))
        (is (= 0 (:offset clamped)))))))

(deftest find-related-context-defaults-to-start-project-scope
  (with-temp-conn
    (fn [conn]
      (seed-query-fixture! conn)
      (store/transact-entities!
       conn
       [{:entity/id "sym1"
         :entity/type :symbol
         :entity/project "p1"
         :entity/name "sym1"
         :entity/body "sym one"
         :symbol/file "f1"}
        {:entity/id "sym2"
         :entity/type :symbol
         :entity/project "p2"
         :entity/name "sym2"
         :entity/body "sym two"
         :symbol/file "f2"}
        {:entity/id "note-cross"
         :entity/type :note
         :entity/project "p1"
         :entity/name "cross ref"
         :entity/body "cross note"
         :entity/refs ["sym2"]}])
      (let [related-default (queries/find-related-context conn {:entity-id "f1"
                                                                :hops 4
                                                                :limit 20})
            default-ids (set (map :entity/id (:related related-default)))
            related-cross (queries/find-related-context conn {:entity-id "f1"
                                                              :project-ids ["p2"]
                                                              :hops 4
                                                              :limit 20})
            cross-ids (set (map :entity/id (:related related-cross)))]
        (is (contains? default-ids "sym1"))
        (is (not (contains? default-ids "sym2")))
        (is (contains? cross-ids "note-cross"))
        (is (contains? cross-ids "sym2")))
      (let [paged (queries/find-related-context conn {:entity-id "f1"
                                                      :project-ids ["p2"]
                                                      :hops 4
                                                      :limit 1
                                                      :offset 1})]
        (is (= 1 (count (:related paged))))
        (is (= 1 (:limit paged)))
        (is (= 1 (:offset paged))))
      (let [clamped (queries/find-related-context conn {:entity-id "f1"
                                                        :project-ids ["p2"]
                                                        :hops 99
                                                        :limit 999
                                                        :offset -5})]
        (is (= 100 (:limit clamped)))
        (is (= 0 (:offset clamped)))
        (is (= 5 (:hops clamped)))))))
