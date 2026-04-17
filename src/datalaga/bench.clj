(ns datalaga.bench
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [datalaga.ingest :as ingest]
            [datalaga.memory.queries :as queries]
            [datalaga.memory.store :as store]
            [datalaga.util :as util])
  (:import [java.nio.file Files Path StandardCopyOption]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def cli-options
  [["-n" "--samples N" "Samples per scenario."
    :default 5
    :parse-fn #(Integer/parseInt %)]
   ["-s" "--seed-file FILE" "Path to the EDN seed dataset."
    :default ingest/default-seed-file]
   ["-r" "--report-file FILE" "Where to write the markdown benchmark report."
    :default "eval/bench-report.md"]
   ["-h" "--help"]])

(def bench-backends
  [:datalevin
   :datascript-sqlite])

(def project-id "project:phoenix-auth")
(def search-query "session generation")

(defn- usage
  [summary]
  (str/join
   \newline
   ["Benchmark Datalevin versus datascript-sqlite for startup and representative operations."
    ""
    "Usage: clojure -M -m datalaga.bench [options]"
    ""
    "Options:"
    summary]))

(defn- eprintln
  [& values]
  (binding [*out* *err*]
    (apply println values)))

(defn- temp-root
  [prefix]
  (.toFile
   (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- backend-db-path
  [root backend]
  (.getAbsolutePath
   (case (store/normalize-backend backend)
     :datascript-sqlite (io/file root "memory.sqlite")
     (io/file root))))

(defn- delete-path!
  [path]
  (let [root (io/file path)]
    (when (.exists root)
      (if (.isDirectory root)
        (doseq [f (reverse (file-seq root))]
          (io/delete-file f true))
        (io/delete-file root true)))))

(defn- ensure-parent-dir!
  [file]
  (when-let [parent (.getParentFile (io/file file))]
    (.mkdirs parent)))

(defn- copy-path!
  [source target]
  (let [src-file (io/file source)
        dst-file (io/file target)]
    (ensure-parent-dir! dst-file)
    (if (.isDirectory src-file)
      (do
        (.mkdirs dst-file)
        (let [src-path (.toPath src-file)]
          (doseq [file (file-seq src-file)]
            (let [relative (.relativize src-path (.toPath file))
                  target-path (.resolve (.toPath dst-file) relative)]
              (if (.isDirectory file)
                (Files/createDirectories target-path (make-array java.nio.file.attribute.FileAttribute 0))
                (do
                  (Files/createDirectories (.getParent target-path) (make-array java.nio.file.attribute.FileAttribute 0))
                  (Files/copy (.toPath file)
                              target-path
                              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))))))
      (Files/copy (.toPath src-file)
                  (.toPath dst-file)
                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
  target)

(defn- command-result!
  [args]
  (let [command (mapv str args)
        builder (doto (ProcessBuilder. command)
                  (.directory (io/file ".")))
        started-at (System/nanoTime)
        process (.start builder)
        stdout-future (future (slurp (io/reader (.getInputStream process))))
        stderr-future (future (slurp (io/reader (.getErrorStream process))))
        exit-code (.waitFor process)
        elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)
        stdout (deref stdout-future 5000 "")
        stderr (deref stderr-future 5000 "")]
    {:command command
     :exit-code exit-code
     :elapsed-ms elapsed-ms
     :stdout stdout
     :stderr stderr}))

(defn- require-success!
  [{:keys [exit-code command stdout stderr] :as result}]
  (when-not (zero? exit-code)
    (throw (ex-info "Benchmark command failed"
                    {:command command
                     :exit-code exit-code
                     :stdout stdout
                     :stderr stderr})))
  result)

(defn- send-json!
  [writer message]
  (.write writer (json/write-str message))
  (.write writer "\n")
  (.flush writer))

(defn- read-json!
  [reader]
  (some-> (.readLine reader)
          (json/read-str :key-fn keyword)))

(defn- mcp-initialize-result!
  [backend db-path]
  (let [command ["./bin/start-mcp"
                 "--backend" (name backend)
                 "--db-path" db-path]
        builder (doto (ProcessBuilder. command)
                  (.directory (io/file ".")))
        started-at (System/nanoTime)
        process (.start builder)
        stdout (io/reader (.getInputStream process))
        stderr-future (future (slurp (io/reader (.getErrorStream process))))
        stdin (io/writer (.getOutputStream process))]
    (try
      (send-json! stdin {:jsonrpc "2.0"
                         :id 1
                         :method "initialize"
                         :params {:protocolVersion "2025-03-26"
                                  :capabilities {}
                                  :clientInfo {:name "bench"
                                               :version "0.1.0"}}})
      (let [response (read-json! stdout)
            elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)]
        (when (or (nil? response) (:error response))
          (throw (ex-info "MCP initialize failed"
                          {:command command
                           :response response
                           :stderr (deref stderr-future 1000 "")})))
        {:command command
         :elapsed-ms elapsed-ms
         :response response
         :stderr (deref stderr-future 1000 "")})
      (finally
        (try (.close stdin) (catch Exception _))
        (try (.close stdout) (catch Exception _))
        (.destroy process)
        (when-not (.waitFor process 2000 TimeUnit/MILLISECONDS)
          (.destroyForcibly process)
          (.waitFor process 2000 TimeUnit/MILLISECONDS))))))

(defn- time-call-ms
  [f]
  (let [started-at (System/nanoTime)
        value (f)]
    {:elapsed-ms (/ (- (System/nanoTime) started-at) 1e6)
     :value value}))

(defn- median
  [values]
  (let [sorted (vec (sort values))
        count-values (count sorted)
        mid (quot count-values 2)]
    (if (odd? count-values)
      (nth sorted mid)
      (/ (+ (nth sorted (dec mid))
            (nth sorted mid))
         2.0))))

(defn- stats
  [samples]
  (let [sample-count (count samples)
        total (reduce + 0.0 samples)]
    {:sample-count sample-count
     :samples (mapv #(Math/round (double %)) samples)
     :mean-ms (/ total sample-count)
     :median-ms (median samples)
     :min-ms (reduce min samples)
     :max-ms (reduce max samples)}))

(defn- ratio
  [baseline challenger]
  (when (pos? baseline)
    (/ challenger baseline)))

(defn- describe-ratio
  [datalevin-mean datascript-mean]
  (cond
    (nil? datalevin-mean) nil
    (nil? datascript-mean) nil
    (< datascript-mean datalevin-mean)
    {:winner :datascript-sqlite
     :speedup (/ datalevin-mean datascript-mean)}

    (> datascript-mean datalevin-mean)
    {:winner :datalevin
     :speedup (/ datascript-mean datalevin-mean)}

    :else
    {:winner :tie
     :speedup 1.0}))

(defn- seeded-fixture!
  [backend seed-file]
  (let [root (temp-root (str "datalaga-bench-seed-" (name backend) "-"))
        db-path (backend-db-path root backend)]
    (ingest/seed! {:backend backend
                   :db-path db-path
                   :seed-file seed-file})
    {:root root
     :db-path db-path}))

(defn- empty-db-path!
  [backend]
  (let [root (temp-root (str "datalaga-bench-empty-" (name backend) "-"))
        db-path (backend-db-path root backend)]
    {:root root
     :db-path db-path}))

(defn- benchmark-seed-dataset
  [backend sample-count seed-file]
  (let [samples (vec
                 (for [_ (range sample-count)]
                   (let [{:keys [root db-path]} (empty-db-path! backend)]
                     (try
                       (:elapsed-ms
                        (time-call-ms #(ingest/seed! {:backend backend
                                                      :db-path db-path
                                                      :seed-file seed-file})))
                       (finally
                         (delete-path! root))))))]
    {:id "seed_dataset"
     :label "Seed dataset"
     :backend backend
     :stats (stats samples)}))

(defn- benchmark-in-process-search
  [backend sample-count seed-file]
  (let [{:keys [root db-path]} (seeded-fixture! backend seed-file)]
    (try
      (let [samples (vec
                     (for [_ (range sample-count)]
                       (let [{:keys [elapsed-ms value]}
                             (time-call-ms
                              #(let [conn (store/open-conn {:backend backend :db-path db-path})]
                                 (try
                                   (queries/search-notes conn {:project-id project-id
                                                               :query search-query
                                                               :limit 10})
                                   (finally
                                     (store/close! conn)))))]
                         (when-not (seq (:matches value))
                           (throw (ex-info "In-process search benchmark returned no matches"
                                           {:backend backend
                                            :query search-query})))
                         elapsed-ms)))]
        {:id "in_process_search_notes"
         :label "In-process open + search_notes"
         :backend backend
         :stats (stats samples)})
      (finally
        (delete-path! root)))))

(defn- benchmark-cli-list-projects
  [backend sample-count]
  (let [samples (vec
                 (for [_ (range sample-count)]
                   (let [{:keys [root db-path]} (empty-db-path! backend)]
                     (try
                       (:elapsed-ms
                        (require-success!
                         (command-result! ["./bin/datalaga"
                                           "--backend" (name backend)
                                           "--db-path" db-path
                                           "list_projects"
                                           "-f" "json"])))
                       (finally
                         (delete-path! root))))))]
    {:id "cli_list_projects"
     :label "CLI list_projects (empty db)"
     :backend backend
     :stats (stats samples)}))

(defn- benchmark-cli-search
  [backend sample-count seed-file]
  (let [{:keys [root db-path]} (seeded-fixture! backend seed-file)]
    (try
      (let [samples (vec
                     (for [_ (range sample-count)]
                       (let [{:keys [elapsed-ms stdout] :as result}
                             (require-success!
                              (command-result! ["./bin/datalaga"
                                                "--backend" (name backend)
                                                "--db-path" db-path
                                                "search_notes"
                                                "--project_id" project-id
                                                "--query" search-query
                                                "--limit" "10"
                                                "-f" "json"]))]
                         (when-not (re-find #"note:" stdout)
                           (throw (ex-info "CLI search benchmark returned no note ids"
                                           {:backend backend
                                            :stdout stdout
                                            :result result})))
                         elapsed-ms)))]
        {:id "cli_search_notes"
         :label "CLI search_notes (seeded db)"
         :backend backend
         :stats (stats samples)})
      (finally
        (delete-path! root)))))

(defn- benchmark-cli-record-tool-run
  [backend sample-count seed-file]
  (let [{:keys [root db-path]} (seeded-fixture! backend seed-file)]
    (try
      (let [samples (vec
                     (for [idx (range sample-count)]
                       (let [sample-root (temp-root (str "datalaga-bench-write-" (name backend) "-" idx "-"))
                             sample-db-path (backend-db-path sample-root backend)]
                         (try
                           (copy-path! db-path sample-db-path)
                           (:elapsed-ms
                            (require-success!
                             (command-result! ["./bin/datalaga"
                                               "--backend" (name backend)
                                               "--db-path" sample-db-path
                                               "record_tool_run"
                                               "--run_id" (str "run:bench:" (name backend) ":" idx)
                                               "--project_id" project-id
                                               "--command" "npm test"
                                               "--exit_code" "0"
                                               "-f" "json"])))
                           (finally
                             (delete-path! sample-root))))))]
        {:id "cli_record_tool_run"
         :label "CLI record_tool_run (seeded db)"
         :backend backend
         :stats (stats samples)})
      (finally
        (delete-path! root)))))

(defn- benchmark-mcp-initialize
  [backend sample-count seed-file]
  (let [{:keys [root db-path]} (seeded-fixture! backend seed-file)]
    (try
      (let [samples (vec
                     (for [_ (range sample-count)]
                       (:elapsed-ms (mcp-initialize-result! backend db-path))))]
        {:id "mcp_initialize"
         :label "MCP initialize"
         :backend backend
         :stats (stats samples)})
      (finally
        (delete-path! root)))))

(defn- scenario-order
  []
  ["seed_dataset"
   "in_process_search_notes"
   "cli_list_projects"
   "cli_search_notes"
   "cli_record_tool_run"
   "mcp_initialize"])

(defn- collect-benchmarks
  [sample-count seed-file]
  (let [results (mapcat (fn [backend]
                          [(benchmark-seed-dataset backend sample-count seed-file)
                           (benchmark-in-process-search backend sample-count seed-file)
                           (benchmark-cli-list-projects backend sample-count)
                           (benchmark-cli-search backend sample-count seed-file)
                           (benchmark-cli-record-tool-run backend sample-count seed-file)
                           (benchmark-mcp-initialize backend sample-count seed-file)])
                        bench-backends)]
    (->> results
         (group-by :id)
         (map (fn [[id scenario-results]]
                (let [by-backend (into {}
                                       (map (juxt :backend :stats))
                                       scenario-results)
                      datalevin-mean (get-in by-backend [:datalevin :mean-ms])
                      datascript-mean (get-in by-backend [:datascript-sqlite :mean-ms])]
                  {:id id
                   :label (:label (first scenario-results))
                   :by-backend by-backend
                   :comparison (describe-ratio datalevin-mean datascript-mean)})))
         (sort-by (fn [scenario]
                    (.indexOf ^java.util.List (scenario-order) (:id scenario))))
         vec)))

(defn- render-report
  [{:keys [sample-count scenarios report-file]}]
  (str
   "# Backend Benchmark Report\n\n"
   "Generated on " (util/now-iso) ".\n\n"
   "Samples per scenario: `" sample-count "`\n\n"
   "| Scenario | Datalevin mean | DataScript+SQLite mean | Median delta | Faster backend |\n"
   "| --- | ---: | ---: | ---: | --- |\n"
   (apply str
          (for [{:keys [label by-backend comparison]} scenarios]
            (let [datalevin (get by-backend :datalevin)
                  datascript (get by-backend :datascript-sqlite)
                  delta (- (:median-ms datascript) (:median-ms datalevin))
                  winner (case (:winner comparison)
                           :datascript-sqlite "datascript-sqlite"
                           :datalevin "datalevin"
                           :tie "tie"
                           "n/a")]
              (format "| %s | %.0f ms | %.0f ms | %.0f ms | %s |\n"
                      label
                      (:mean-ms datalevin)
                      (:mean-ms datascript)
                      delta
                      winner))))
   "\n"
   (apply str
          (for [{:keys [label by-backend comparison]} scenarios]
            (str "## " label "\n\n"
                 "- Datalevin: " (pr-str (select-keys (get by-backend :datalevin)
                                                      [:sample-count :samples :mean-ms :median-ms :min-ms :max-ms])) "\n"
                 "- DataScript+SQLite: " (pr-str (select-keys (get by-backend :datascript-sqlite)
                                                              [:sample-count :samples :mean-ms :median-ms :min-ms :max-ms])) "\n"
                 (when comparison
                   (format "- Winner: `%s` (%.2fx)\n"
                           (name (:winner comparison))
                           (:speedup comparison)))
                 "\n")))
   "Report file: `" report-file "`\n"))

(defn- run-benchmarks!
  [{:keys [samples seed-file report-file]}]
  (let [scenario-results (collect-benchmarks samples seed-file)
        summary {:generated-at (util/now-iso)
                 :sample-count samples
                 :query search-query
                 :scenarios scenario-results
                 :report-file report-file}]
    (ensure-parent-dir! report-file)
    (spit report-file (render-report summary))
    summary))

(defn -main
  [& args]
  (let [{:keys [options summary errors]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (println (usage summary))

      (seq errors)
      (doseq [error errors]
        (eprintln error))

      :else
      (pprint/pprint
       (util/json-friendly (run-benchmarks! options))))))
