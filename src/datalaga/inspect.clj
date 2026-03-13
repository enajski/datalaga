(ns datalaga.inspect
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [datalaga.ingest :as ingest]
            [datalaga.memory.queries :as queries]
            [datalaga.memory.store :as store]
            [datalaga.util :as util]))

(def cli-options
  [["-d" "--db-path PATH" "Path to the Datalevin database directory."
    :default store/default-db-path]
   ["-s" "--seed-file FILE" "Path to the EDN seed dataset."
    :default ingest/default-seed-file]
   [nil "--seed-before-run" "Seed the database before running inspect commands."
    :default false]
   ["-h" "--help"]])

(defn- usage
  [summary]
  (str/join
   \newline
   ["Inspect seeded memory data from the terminal."
    ""
    "Usage: clojure -M:inspect [options] command [args]"
    ""
    "Commands:"
    "  summary PROJECT_ID"
    "  entity ENTITY_ID"
    "  symbol SYMBOL_ID"
    "  task TASK_ID"
    "  file FILE_ID"
    "  notes PROJECT_ID QUERY"
    "  failures PROJECT_ID"
    "  active-tasks PROJECT_ID"
    "  prior-decisions TASK_ID"
    ""
    "Options:"
    summary]))

(defn- print-result
  [value]
  (pprint/pprint (util/json-friendly value)))

(defn- print-error!
  [message data]
  (binding [*out* *err*]
    (println (str "Error: " message))
    (when (seq data)
      (pprint/pprint (util/json-friendly data)))))

(defn- dispatch-command
  [conn command args]
  (let [db-value (store/db conn)]
    (case command
      "summary" (queries/project-summary conn (first args))
      "entity" (store/pull-entity-by-id db-value (first args))
      "symbol" (queries/get-symbol-memory conn {:symbol-id (first args)})
      "task" (queries/get-task-timeline conn {:task-id (first args)})
      "file" (queries/context-for-file conn (first args))
      "notes" (queries/search-notes conn {:project-id (first args)
                                          :query (str/join " " (rest args))})
      "failures" {:recent_failures (queries/recent-failures conn (first args))}
      "active-tasks" {:active_tasks (queries/active-tasks conn (first args))}
      "prior-decisions" {:decisions (queries/prior-decisions-for-task conn (first args))}
      (throw (ex-info "Unknown inspect command" {:command command
                                                 :args args})))))

(defn -main
  [& args]
  (let [{:keys [options summary errors arguments]} (parse-opts args cli-options)
        [command & command-args] arguments]
    (cond
      (:help options)
      (println (usage summary))

      (seq errors)
      (do
        (doseq [error errors]
          (binding [*out* *err*]
            (println error)))
        (System/exit 1))

      (nil? command)
      (println (usage summary))

      :else
      (try
        (when (:seed-before-run options)
          (ingest/seed! {:db-path (:db-path options) :seed-file (:seed-file options)}))
        (let [conn (store/open-conn (:db-path options))]
          (try
            (print-result (dispatch-command conn command command-args))
            (finally
              (store/close! conn))))
        (catch clojure.lang.ExceptionInfo ex
          (print-error! (.getMessage ex) (ex-data ex))
          (System/exit 1))
        (catch Throwable ex
          (print-error! "Unexpected inspect failure" {:message (.getMessage ex)})
          (System/exit 1))))))
