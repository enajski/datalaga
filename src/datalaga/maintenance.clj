(ns datalaga.maintenance
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [datalaga.ingest :as ingest]
            [datalaga.memory.maintenance :as maintenance]
            [datalaga.memory.store :as store]
            [datalaga.util :as util]))

(def cli-options
  [["-d" "--db-path PATH" "Path to the Datalevin database directory."
    :default store/default-db-path]
   ["-s" "--seed-file FILE" "Path to the EDN seed dataset."
    :default ingest/default-seed-file]
   ["-p" "--project-id PROJECT_ID" "Project id to normalize."]
   ["-m" "--mode MODE" "Normalization mode: dry_run | apply."
    :default "dry_run"]
   ["-o" "--operations OPS" "Comma-separated operations (default: all)."]
   ["-x" "--max-changes N" "Safety cap for apply mode."
    :default 500
    :parse-fn #(Long/parseLong %)]
   ["-i" "--migration-id ID" "Optional idempotency id for safe re-runs."]
   ["-h" "--help"]])

(defn- usage
  [summary]
  (str/join
   \newline
   ["Normalize project memory with dry-run/apply modes."
    ""
    "Usage: clojure -M:maintenance [options]"
    ""
    "Examples:"
    "  ./bin/normalize-memory --project-id project:yoyo-evolve --mode dry_run"
    "  ./bin/normalize-memory --project-id project:yoyo-evolve --mode apply --migration-id migration:v1"
    "  ./bin/normalize-memory --project-id project:yoyo-evolve --mode apply --operations normalize_entity_types,backfill_error_resolution"
    ""
    "Options:"
    summary]))

(defn- parse-operations
  [operations]
  (when (and operations (not (str/blank? operations)))
    (->> (str/split operations #",")
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn -main
  [& args]
  (let [{:keys [options summary errors]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (println (usage summary))

      (seq errors)
      (doseq [error errors]
        (binding [*out* *err*]
          (println error)))

      (str/blank? (:project-id options))
      (binding [*out* *err*]
        (println "--project-id is required"))

      :else
      (do
        (ingest/seed! {:db-path (:db-path options) :seed-file (:seed-file options)})
        (let [conn (store/open-conn (:db-path options))]
          (try
            (pprint/pprint
             (util/json-friendly
              (maintenance/normalize-project-memory!
               conn
               {:project_id (:project-id options)
                :mode (:mode options)
                :operations (parse-operations (:operations options))
                :max_changes (:max-changes options)
                :migration_id (:migration-id options)})))
            (finally
              (store/close! conn))))))))
