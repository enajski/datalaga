(ns datalaga.cli-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datalaga.cli :as cli]
            [datalaga.memory.store :as store]))

(defn- delete-tree!
  [path]
  (let [root (io/file path)]
    (when (.exists root)
      (doseq [f (reverse (file-seq root))]
        (io/delete-file f true)))))

(defn- with-temp-conn
  [f]
  (let [db-path (.getAbsolutePath
                 (.toFile
                  (java.nio.file.Files/createTempDirectory
                   "datalaga-cli-test-db"
                   (make-array java.nio.file.attribute.FileAttribute 0))))
        conn (store/open-conn db-path)]
    (try
      (f conn db-path)
      (finally
        (store/close! conn)
        (delete-tree! db-path)))))

;; ---------------------------------------------------------------------------
;; Argument parsing unit tests
;; ---------------------------------------------------------------------------

(deftest parse-tool-args-simple-flags
  (testing "simple --key value pairs"
    (let [result (#'cli/parse-tool-args ["--project_id" "project:foo"
                                          "--query" "auth"])]
      (is (= "project:foo" (:project_id result)))
      (is (= "auth" (:query result)))))

  (testing "integer values"
    (let [result (#'cli/parse-tool-args ["--limit" "10" "--offset" "5"])]
      (is (= 10 (:limit result)))
      (is (= 5 (:offset result)))))

  (testing "boolean flag (no value)"
    (let [result (#'cli/parse-tool-args ["--verbose"])]
      (is (= true (:verbose result)))))

  (testing "JSON array value"
    (let [result (#'cli/parse-tool-args ["--touched_file_paths"
                                          "[\"src/a.clj\",\"src/b.clj\"]"])]
      (is (= ["src/a.clj" "src/b.clj"] (:touched_file_paths result)))))

  (testing "JSON object value"
    (let [result (#'cli/parse-tool-args ["--provenance"
                                          "{\"source\":\"cli\"}"])]
      (is (= {:source "cli"} (:provenance result)))))

  (testing "repeated key becomes vector"
    (let [result (#'cli/parse-tool-args ["--tag" "a" "--tag" "b"])]
      (is (= ["a" "b"] (:tag result))))))

(deftest normalize-tool-name-mapping
  (testing "underscore names pass through"
    (is (= "list_projects" (#'cli/normalize-tool-name "list_projects"))))

  (testing "hyphens converted to underscores"
    (is (= "list_projects" (#'cli/normalize-tool-name "list-projects")))
    (is (= "record_tool_run" (#'cli/normalize-tool-name "record-tool-run"))))

  (testing "unknown tool returns nil"
    (is (nil? (#'cli/normalize-tool-name "nonexistent_tool")))))

(deftest try-parse-json-value-cases
  (testing "plain strings stay as strings"
    (is (= "project:foo" (#'cli/try-parse-json-value "project:foo")))
    (is (= "auth" (#'cli/try-parse-json-value "auth"))))

  (testing "integers parsed"
    (is (= 42 (#'cli/try-parse-json-value "42")))
    (is (= -1 (#'cli/try-parse-json-value "-1"))))

  (testing "booleans parsed"
    (is (= true (#'cli/try-parse-json-value "true")))
    (is (= false (#'cli/try-parse-json-value "false"))))

  (testing "null parsed"
    (is (nil? (#'cli/try-parse-json-value "null"))))

  (testing "JSON array parsed"
    (is (= ["a" "b"] (#'cli/try-parse-json-value "[\"a\",\"b\"]"))))

  (testing "JSON object parsed"
    (is (= {:k "v"} (#'cli/try-parse-json-value "{\"k\":\"v\"}")))))

;; ---------------------------------------------------------------------------
;; Integration tests: call-tool! through CLI layer
;; ---------------------------------------------------------------------------

(deftest cli-ensure-project-and-list
  (with-temp-conn
    (fn [conn _db-path]
      (testing "ensure_project creates a project"
        (let [result (datalaga.mcp.server/call-tool!
                      conn "ensure_project"
                      {:project_id "project:cli-test"
                       :name "CLI Test Project"
                       :summary "A project created from the CLI tests"})]
          (is (true? (:created result)))
          (is (= "project:cli-test" (get-in result [:project :entity/id])))))

      (testing "list_projects returns the created project"
        (let [result (datalaga.mcp.server/call-tool! conn "list_projects" {})]
          (is (seq (:projects result)))
          (is (some #(= "project:cli-test" (:entity/id %))
                    (:projects result))))))))

(deftest cli-search-and-remember
  (with-temp-conn
    (fn [conn _db-path]
      (datalaga.mcp.server/call-tool!
       conn "ensure_project"
       {:project_id "project:cli-test" :name "CLI Test"})

      (testing "remember_fact and search_notes"
        (datalaga.mcp.server/call-tool!
         conn "remember_fact"
         {:entity_id "note:cli-test-1"
          :entity_type "note"
          :name "CLI finding"
          :summary "Found that CLI works"
          :body "The CLI integration is working correctly"
          :project_id "project:cli-test"})

        (let [result (datalaga.mcp.server/call-tool!
                      conn "search_notes"
                      {:query "CLI works"
                       :project_id "project:cli-test"})]
          (is (seq (:matches result))))))))

(deftest cli-record-tool-run
  (with-temp-conn
    (fn [conn _db-path]
      (datalaga.mcp.server/call-tool!
       conn "ensure_project"
       {:project_id "project:cli-test" :name "CLI Test"})

      (testing "record_tool_run via call-tool!"
        (let [result (datalaga.mcp.server/call-tool!
                      conn "record_tool_run"
                      {:run_id "run:cli-test-1"
                       :project_id "project:cli-test"
                       :command "clojure -M:test"
                       :exit_code 0
                       :output "All tests passed"})]
          (is (= "run:cli-test-1" (get-in result [:entity :entity/id])))
          (is (= :success (get-in result [:entity :entity/status]))))))))
