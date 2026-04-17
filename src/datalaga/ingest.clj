(ns datalaga.ingest
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [datalaga.memory.store :as store]
            [datalaga.util :as util]))

(def default-seed-file "examples/seed-data.edn")

(def cli-options
  [["-b" "--backend BACKEND" "Storage backend: datalevin | datascript-sqlite."
    :default (name store/default-backend)]
   ["-d" "--db-path PATH" "Path to the backend store (directory for Datalevin, SQLite file for datascript-sqlite)."
    :default store/default-db-path]
   ["-s" "--seed-file FILE" "Path to the EDN seed dataset."
    :default default-seed-file]
   ["-h" "--help"]])

(defn- join-body
  [& parts]
  (->> parts
       flatten
       (remove nil?)
       (map str)
       (remove str/blank?)
       (str/join "\n")))

(defn- base-entity
  [artifact entity-type]
  (let [timestamp (or (:timestamp artifact) (util/now-iso))]
    (util/compact-map
     {:entity/id (:entity/id artifact)
      :entity/type entity-type
      :entity/name (:name artifact)
      :entity/status (:status artifact)
      :entity/path (:path artifact)
      :entity/kind (:kind artifact)
      :entity/language (:language artifact)
      :entity/summary (:summary artifact)
      :entity/source (or (:source artifact) (name entity-type))
      :entity/source-ref (:source-ref artifact)
      :entity/created-at timestamp
      :entity/updated-at (or (:updated-at artifact) timestamp)
      :entity/tags (vec (:tags artifact))
      :entity/project (:project/id artifact)
      :entity/session (:session/id artifact)
      :entity/task (:task/id artifact)
      :entity/file (:file/id artifact)
      :entity/symbol (:symbol/id artifact)
      :entity/refs (vec (:refs artifact))})))

(defmulti normalize-artifact :artifact/type)

(defmethod normalize-artifact :project
  [artifact]
  [(assoc (base-entity artifact :project)
          :project/root (:root artifact)
          :entity/body (join-body (:name artifact) (:summary artifact) (:root artifact) (:tags artifact)))])

(defmethod normalize-artifact :file
  [artifact]
  [(assoc (base-entity artifact :file)
          :file/module (:module artifact)
          :entity/body (join-body (:path artifact) (:module artifact) (:summary artifact)))])

(defmethod normalize-artifact :symbol
  [artifact]
  [(assoc (base-entity artifact :symbol)
          :symbol/file (:file/id artifact)
          :symbol/qualified-name (:qualified-name artifact)
          :symbol/signature (:signature artifact)
          :symbol/kind (:kind artifact)
          :entity/body (join-body (:qualified-name artifact) (:signature artifact) (:summary artifact)))
   {:entity/id (:file/id artifact)
    :file/contains-symbols [(:entity/id artifact)]}])

(defmethod normalize-artifact :task
  [artifact]
  [(assoc (base-entity artifact :task)
          :task/description (:description artifact)
          :task/priority (:priority artifact)
          :task/touched-files (vec (:touched-file-ids artifact))
          :task/related-symbols (vec (:related-symbol-ids artifact))
          :entity/body (join-body (:name artifact)
                                  (:description artifact)
                                  (:summary artifact)
                                  (:touched-file-ids artifact)
                                  (:related-symbol-ids artifact)))])

(defmethod normalize-artifact :session
  [artifact]
  [(assoc (base-entity artifact :session)
          :session/agent (:agent artifact)
          :session/started-at (:started-at artifact)
          :session/ended-at (:ended-at artifact)
          :entity/body (join-body (:name artifact) (:summary artifact) (:agent artifact)))])

(defmethod normalize-artifact :tool-run
  [artifact]
  [(assoc (base-entity artifact :tool-run)
          :tool-run/command (:command artifact)
          :tool-run/phase (:phase artifact)
          :tool-run/exit-code (:exit-code artifact)
          :tool-run/output (:output artifact)
          :tool-run/touched-files (vec (:touched-file-ids artifact))
          :entity/body (join-body (:name artifact)
                                  (:summary artifact)
                                  (:command artifact)
                                  (:output artifact)))])

(defmethod normalize-artifact :error
  [artifact]
  [(assoc (base-entity artifact :error)
          :error/category (:category artifact)
          :error/details (:details artifact)
          :error/tool-run (:tool-run/id artifact)
          :error/related-symbols (vec (:related-symbol-ids artifact))
          :entity/body (join-body (:name artifact)
                                  (:summary artifact)
                                  (:details artifact)
                                  (:related-symbol-ids artifact)))])

(defmethod normalize-artifact :observation
  [artifact]
  [(assoc (base-entity artifact :observation)
          :observation/tool-run (:tool-run/id artifact)
          :observation/confidence (:confidence artifact)
          :observation/detail (:detail artifact)
          :entity/body (join-body (:name artifact) (:summary artifact) (:detail artifact)))])

(defmethod normalize-artifact :decision
  [artifact]
  [(assoc (base-entity artifact :decision)
          :decision/rationale (:rationale artifact)
          :decision/outcome (:outcome artifact)
          :decision/alternatives (vec (:alternatives artifact))
          :decision/related-files (vec (:related-file-ids artifact))
          :decision/related-symbols (vec (:related-symbol-ids artifact))
          :entity/body (join-body (:name artifact)
                                  (:summary artifact)
                                  (:rationale artifact)
                                  (:outcome artifact)
                                  (:alternatives artifact)))])

(defmethod normalize-artifact :patch
  [artifact]
  [(assoc (base-entity artifact :patch)
          :patch/commit (:commit artifact)
          :patch/diff-summary (:diff-summary artifact)
          :patch/modified-files (vec (:modified-file-ids artifact))
          :patch/modified-symbols (vec (:modified-symbol-ids artifact))
          :patch/decision (:decision/id artifact)
          :patch/tool-run (:tool-run/id artifact)
          :entity/body (join-body (:name artifact)
                                  (:summary artifact)
                                  (:diff-summary artifact)
                                  (:modified-file-ids artifact)
                                  (:modified-symbol-ids artifact)))])

(defmethod normalize-artifact :note
  [artifact]
  [(assoc (base-entity artifact :note)
          :note/content (:content artifact)
          :note/refers-to (vec (:ref-ids artifact))
          :entity/body (join-body (:name artifact) (:summary artifact) (:content artifact)))])

(defmethod normalize-artifact :event
  [artifact]
  [(assoc (base-entity artifact :event)
          :event/kind (:kind artifact)
          :event/at (:timestamp artifact)
          :event/session (:session/id artifact)
          :event/subjects (vec (:subject-ids artifact))
          :entity/body (join-body (:name artifact) (:summary artifact) (:subject-ids artifact)))])

(defmethod normalize-artifact :link
  [artifact]
  [(assoc (base-entity artifact :link)
          :link/from (:from/id artifact)
          :link/to (:to/id artifact)
          :link/type (:link-type artifact)
          :link/explanation (:explanation artifact)
          :link/evidence (vec (:evidence-ids artifact))
          :entity/body (join-body (:name artifact) (:summary artifact) (:explanation artifact)))])

(defmethod normalize-artifact :default
  [artifact]
  (throw (ex-info "Unsupported artifact type" {:artifact artifact})))

(defn load-artifacts
  [seed-file]
  (-> seed-file
      io/file
      slurp
      edn/read-string))

(defn normalize-artifacts
  [artifacts]
  (mapcat normalize-artifact artifacts))

(defn seed!
  ([]
   (seed! {:backend store/default-backend
           :db-path store/default-db-path
           :seed-file default-seed-file}))
  ([{:keys [backend db-path seed-file]}]
   (let [artifacts (load-artifacts seed-file)
         entities (normalize-artifacts artifacts)
         conn (store/open-conn {:backend backend :db-path db-path})]
     (try
       (let [report (store/transact-entities! conn entities)
             db-value (store/db conn)]
         {:backend (name (store/backend-key conn))
          :db-path db-path
          :seed-file seed-file
          :project-ids (store/all-project-ids db-value)
          :entity-count (store/entity-count db-value)
          :seed-report report})
       (finally
         (store/close! conn))))))

(defn usage
  [summary]
  (->> ["Seed the coding-memory store with the synthetic coding-memory dataset."
        ""
        "Usage: clojure -M:seed [options]"
        ""
        "Options:"
        summary]
       (str/join \newline)))

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

      :else
      (let [result (seed! options)]
        (println (pr-str (util/json-friendly result)))))))
