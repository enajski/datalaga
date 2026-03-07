(ns datalaga.mcp.server
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [datalaga.ingest :as ingest]
            [datalaga.memory.maintenance :as maintenance]
            [datalaga.memory.queries :as queries]
            [datalaga.memory.schema :as memory-schema]
            [datalaga.memory.store :as store]
            [datalaga.util :as util]))

(def protocol-version "2025-03-26")

(def cli-options
  [["-d" "--db-path PATH" "Path to the Datalevin database directory."
    :default store/default-db-path]
   ["-s" "--seed-file FILE" "Path to the EDN seed dataset."
    :default ingest/default-seed-file]
   ["-h" "--help"]])

(defn- eprintln
  [& values]
  (binding [*out* *err*]
    (apply println values)))

(defn- usage
  [summary]
  (str/join
   \newline
   ["Start the Datalevin-backed MCP server on stdio."
    ""
    "Usage: clojure -M:mcp [options]"
    ""
    "Options:"
    summary]))

(defn- deep-keywordize
  [value]
  (cond
    (map? value) (into {}
                       (map (fn [[k v]]
                              [(if (string? k) (util/->keyword k) k)
                               (deep-keywordize v)]))
                       value)
    (sequential? value) (mapv deep-keywordize value)
    :else value))

(defn- pretty-text
  [value]
  (with-out-str
    (pprint/pprint (util/json-friendly value))))

(defn- tool-result
  [value]
  (let [json-value (util/json-friendly value)]
    {:content [{:type "text"
                :text (pretty-text json-value)}]
     :structuredContent json-value}))

(defn- tool-error
  [message data]
  {:content [{:type "text"
              :text (pretty-text {:error message :data (util/json-friendly data)})}]
   :structuredContent {:error message
                       :data (util/json-friendly data)}
   :isError true})

(defn- response
  [id result]
  {:jsonrpc "2.0"
   :id id
   :result result})

(defn- error-response
  [id code message data]
  {:jsonrpc "2.0"
   :id id
   :error {:code code
           :message message
           :data (util/json-friendly data)}})

(defn- write-message!
  [writer message]
  (.write writer (json/write-str message))
  (.write writer "\n")
  (.flush writer))

(defn- default-provenance
  [arguments default-source]
  (let [provenance (:provenance arguments)]
    {:entity/source (or (:source provenance) default-source)
     :entity/source-ref (:source_ref provenance)}))

(declare entity-project-id normalize-task-status compound-command?)

(def tool-allowed-args
  {"ensure_project" #{:project_id :name :summary :body :root :timestamp :provenance}
   "ensure_task" #{:project_id :task_id :name :summary :body :description :status :priority
                   :session_id :timestamp :provenance}
   "list_projects" #{}
   "upsert_code_entity" #{:project_id :kind :file_id :file_path :file_name :module :language
                          :symbol_id :symbol_name :qualified_name :signature :symbol_kind
                          :summary :body :file_summary :file_body :timestamp :provenance}
   "search_entities" #{:project_id :query :mode :entity_type :limit}
   "remember_fact" #{:entity_id :entity_type :name :summary :body :project_id :session_id :task_id
                     :timestamp :attributes :relationships :external_refs :provenance}
   "record_event" #{:event_id :kind :name :summary :project_id :session_id :task_id :timestamp
                    :subject_ids :subject_file_paths :provenance}
   "record_tool_run" #{:run_id :name :summary :body :project_id :session_id :task_id :command :phase
                       :exit_code :output :touched_file_ids :touched_file_paths :supersedes_run_ids :retries_of_run_ids
                       :timestamp :provenance}
   "record_error" #{:error_id :name :summary :body :details :project_id :session_id :task_id
                    :tool_run_id :category :status :related_symbol_ids :related_entity_ids :related_file_paths
                    :timestamp :provenance}
   "link_entities" #{:link_id :name :summary :body :project_id :session_id :task_id :from_id :to_id
                     :link_type :explanation :evidence_ids :timestamp :provenance}
   "search_notes" #{:project_id :project_ids :query :limit}
   "find_related_context" #{:entity_id :project_ids :limit :hops}
   "get_symbol_memory" #{:symbol_id :limit}
   "get_task_timeline" #{:task_id}
   "summarize_project_memory" #{:project_id}
   "normalize_project_memory" #{:project_id :mode :operations :max_changes :migration_id :provenance}})

(def tool-required-args
  {"ensure_project" #{:project_id}
   "ensure_task" #{:project_id :task_id}
   "list_projects" #{}
   "upsert_code_entity" #{:project_id :kind}
   "search_entities" #{:project_id :query}
   "remember_fact" #{:entity_id :entity_type}
   "record_event" #{:event_id :kind :project_id}
   "record_tool_run" #{:run_id :project_id :command}
   "record_error" #{:error_id :project_id :summary}
   "link_entities" #{:link_id :project_id :from_id :to_id :link_type}
   "search_notes" #{:query}
   "find_related_context" #{:entity_id}
   "get_symbol_memory" #{:symbol_id}
   "get_task_timeline" #{:task_id}
   "summarize_project_memory" #{:project_id}
   "normalize_project_memory" #{:project_id :mode}})

(defn- missing-required-keys
  [required arguments]
  (->> required
       (filter (fn [k]
                 (let [v (get arguments k)]
                   (or (nil? v)
                       (and (string? v) (str/blank? v))))))
       sort
       vec))

(defn- validate-tool-arguments!
  [tool-name arguments]
  (when-let [required (get tool-required-args tool-name)]
    (let [missing (missing-required-keys required (or arguments {}))]
      (when (seq missing)
        (throw (ex-info "Tool is missing required arguments"
                        {:tool_name tool-name
                         :missing_required_arguments missing
                         :required_arguments (->> required sort vec)})))))
  (when-let [allowed (get tool-allowed-args tool-name)]
    (let [incoming (set (keys (or arguments {})))
          unknown (->> incoming (remove allowed) sort vec)]
      (when (seq unknown)
        (let [unsupported-arg-hints
              {"record_tool_run"
               {:status {:hint "record_tool_run infers entity status from exit_code."
                         :recommended-arguments ["exit_code"]}}}
              did-you-mean (->> unknown
                                (map (fn [arg]
                                       (when-let [hint-data (get-in unsupported-arg-hints [tool-name arg])]
                                         {:argument (name arg)
                                          :hint (:hint hint-data)
                                          :recommended_arguments (:recommended-arguments hint-data)})))
                                (remove nil?)
                                vec)]
          (throw (ex-info "Tool received unsupported arguments"
                          (cond-> {:tool_name tool-name
                                   :unsupported_arguments unknown
                                   :allowed_arguments (->> allowed sort vec)}
                            (seq did-you-mean) (assoc :did_you_mean did-you-mean)))))))))

(defn- transact-and-fetch!
  [conn entity]
  (store/transact-entities! conn [entity])
  {:entity (store/pull-entity-by-id (store/db conn) (:entity/id entity))})

(defn- normalize-entity-type
  [raw-entity-type]
  (let [typed (util/->keyword raw-entity-type)]
    (cond
      (memory-schema/entity-types typed)
      {:entity-type typed
       :type-policy "core"}

      (namespace typed)
      {:entity-type typed
       :entity-kind typed
       :type-policy "custom-qualified"}

      :else
      {:entity-type (keyword "custom" (name typed))
       :entity-kind typed
       :type-policy "custom-promoted"})))

(defn- ensure-project!
  [conn arguments]
  (let [project-id (:project_id arguments)
        db-value (store/db conn)
        existing (store/pull-entity-by-id db-value project-id)]
    (if existing
      {:created false
       :project existing}
      (let [timestamp (or (:timestamp arguments) (util/now-iso))
            entity (merge (default-provenance arguments "ensure_project")
                          {:entity/id project-id
                           :entity/type :project
                           :entity/name (or (:name arguments) project-id)
                           :entity/summary (:summary arguments)
                           :entity/body (or (:body arguments)
                                            (:summary arguments)
                                            (str "Project " project-id))
                           :entity/created-at timestamp
                           :entity/updated-at timestamp
                           :project/root (:root arguments)})]
        (store/transact-entities! conn [entity])
        {:created true
         :project (store/pull-entity-by-id (store/db conn) project-id)}))))

(defn- ensure-task!
  [conn arguments]
  (let [project-id (:project_id arguments)
        task-id (:task_id arguments)
        timestamp (or (:timestamp arguments) (util/now-iso))
        provenance (default-provenance arguments "ensure_task")
        db-value (store/db conn)
        project (store/pull-entity-by-id db-value project-id)
        existing (store/pull-entity-by-id db-value task-id)]
    (when-not project
      (throw (ex-info "Project does not exist" {:project-id project-id})))
    (cond
      (nil? existing)
      (let [entity (merge provenance
                          {:entity/id task-id
                           :entity/type :task
                           :entity/name (or (:name arguments) task-id)
                           :entity/summary (:summary arguments)
                           :entity/body (or (:body arguments)
                                            (:description arguments)
                                            (:summary arguments)
                                            (str "Task " task-id))
                           :entity/project project-id
                           :entity/session (:session_id arguments)
                           :entity/status (normalize-task-status (or (:status arguments) "open"))
                           :entity/created-at timestamp
                           :entity/updated-at timestamp
                           :task/description (:description arguments)
                           :task/priority (some-> (:priority arguments) util/->keyword)})]
        (store/transact-entities! conn [entity])
        {:created true
         :task (store/pull-entity-by-id (store/db conn) task-id)})

      (and (entity-project-id existing)
           (not= project-id (entity-project-id existing)))
      (throw (ex-info "Task exists under a different project"
                      {:task-id task-id
                       :existing-project-id (entity-project-id existing)
                       :project-id project-id}))

      :else
      (let [entity (merge provenance
                          {:entity/id task-id
                           :entity/project project-id
                           :entity/name (:name arguments)
                           :entity/summary (:summary arguments)
                           :entity/body (or (:body arguments)
                                            (:description arguments)
                                            (:summary arguments))
                           :entity/session (:session_id arguments)
                           :entity/status (some-> (:status arguments) normalize-task-status)
                           :entity/updated-at timestamp
                           :task/description (:description arguments)
                           :task/priority (some-> (:priority arguments) util/->keyword)})]
        (store/transact-entities! conn [entity])
        {:created false
         :updated true
         :task (store/pull-entity-by-id (store/db conn) task-id)}))))

(defn- list-projects!
  [conn]
  (let [db-value (store/db conn)
        entity-ts-ms (fn [entity]
                       (or (some-> (:entity/updated-at entity) .getTime)
                           (some-> (:entity/created-at entity) .getTime)
                           Long/MIN_VALUE))
        project-last-activity (fn [project-id]
                                (let [project-entities (store/project-entities db-value project-id)
                                      best-ms (reduce max Long/MIN_VALUE (map entity-ts-ms project-entities))]
                                  (if (= best-ms Long/MIN_VALUE)
                                    nil
                                    (java.util.Date. best-ms))))
        project-brief (fn [project-id]
                        (let [project (store/pull-entity-by-id db-value project-id)
                              last-activity (project-last-activity project-id)]
                          (cond-> (store/entity-brief project)
                            last-activity (assoc :project/last-activity-at last-activity))))]
    {:projects (->> (store/all-project-ids db-value)
                    (map project-brief)
                    (sort-by (fn [project]
                               [(or (some-> (:project/last-activity-at project) .getTime)
                                    Long/MIN_VALUE)
                                (:entity/id project)]))
                    reverse
                    vec)}))

(defn- entity-project-id
  [entity]
  (or (get-in entity [:entity/project :entity/id])
      (when (= :project (:entity/type entity))
        (:entity/id entity))))

(defn- bare-file-id?
  [file-id]
  (and (string? file-id)
       (str/starts-with? file-id "file:")
       (not (str/starts-with? file-id "file:project:"))))

(defn- project-scoped-file-id
  [project-id file-path]
  (str "file:" project-id ":" file-path))

(defn- scoped-file-id-project-id
  [file-id]
  (some->> file-id
           (re-matches #"^file:(project:[^:]+):.+$")
           second))

(defn- file-ref-id->path
  [file-ref-id]
  (cond
    (not (string? file-ref-id)) nil
    (str/starts-with? file-ref-id "file:project:")
    (or (some->> file-ref-id
                 (re-matches #"^file:project:[^:]+:(.+)$")
                 second)
        (subs file-ref-id (count "file:")))
    (str/starts-with? file-ref-id "file:")
    (subs file-ref-id (count "file:"))
    :else file-ref-id))

(defn- infer-file-id
  [{:keys [project_id file_id file_path]}]
  (or file_id
      (when file_path
        (project-scoped-file-id project_id file_path))))

(defn- infer-symbol-id
  [{:keys [symbol_id file_path symbol_name qualified_name]}]
  (or symbol_id
      (when file_path
        (let [name-part (or symbol_name
                            (when qualified_name
                              (last (str/split qualified_name #"/")))
                            "symbol")]
          (str "symbol:" file_path "#" name-part)))))

(defn- infer-run-name
  [command explicit-name]
  (or explicit-name
      (when (and command (not (str/blank? command)))
        (->> (str/split command #"\s+")
             (take 3)
             (str/join " ")))
      "tool run"))

(defn- run-status
  [exit-code]
  (if (zero? (long (or exit-code 0)))
    :success
    :failed))

(defn- normalize-task-status
  [raw-status]
  (let [status (util/->keyword raw-status)]
    (case status
      :completed :done
      :in-progress :in_progress
      status)))

(defn- compound-command?
  [command]
  (boolean (re-find #"(?:&&|\|\||;|\|)" (or command ""))))

(defn- clean-string
  [value]
  (let [s (some-> value str str/trim)]
    (when-not (str/blank? s)
      s)))

(defn- normalize-external-ref
  [value]
  (cond
    (string? value) (clean-string value)
    (keyword? value) (name value)
    (number? value) (str value)
    (map? value)
    (let [ref-type (clean-string (or (:type value) (get value "type")))
          ref-value (clean-string (or (:value value) (get value "value")))
          ref-url (clean-string (or (:url value) (get value "url")))
          ref-repo (clean-string (or (:repo value) (get value "repo")))]
      (cond
        (and ref-type ref-value) (str ref-type ":" ref-value)
        (and ref-type ref-url) (str ref-type ":" ref-url)
        ref-url ref-url
        ref-repo ref-repo
        :else (clean-string (json/write-str value))))
    :else (clean-string (str value))))

(defn- external-ref-values
  [arguments attributes]
  (->> (concat (util/ensure-vector (:external_refs arguments))
               (util/ensure-vector (:external_refs attributes)))
       (map normalize-external-ref)
       (remove nil?)
       distinct
       vec))

(defn- normalize-file-ref-id
  [project-id value]
  (when-let [raw (clean-string value)]
    (cond
      (str/starts-with? raw "file:project:") raw
      (str/starts-with? raw "file:")
      (if project-id
        (project-scoped-file-id project-id (file-ref-id->path raw))
        raw)
      :else
      (if project-id
        (project-scoped-file-id project-id raw)
        (str "file:" raw)))))

(defn- touched-file-ref-ids
  [arguments]
  (let [explicit-ids (->> (util/ensure-vector (:touched_file_ids arguments))
                          (map #(normalize-file-ref-id (:project_id arguments) %))
                          (remove nil?))
        path-derived-ids (->> (util/ensure-vector (:touched_file_paths arguments))
                              (map #(normalize-file-ref-id (:project_id arguments) %))
                              (remove nil?))]
    (->> (concat explicit-ids path-derived-ids)
         distinct
         vec)))

(defn- file-entity-from-ref-id
  [arguments provenance timestamp file-ref-id]
  (when (str/starts-with? file-ref-id "file:")
    (let [path (file-ref-id->path file-ref-id)
          file-project-id (or (scoped-file-id-project-id file-ref-id)
                              (:project_id arguments))]
      (merge provenance
             {:entity/id file-ref-id
              :entity/type :file
              :entity/name (.getName (io/file path))
              :entity/path path
              :entity/project file-project-id
              :entity/created-at timestamp
              :entity/updated-at timestamp
              :entity/body path}))))

(defn- lower-str
  [value]
  (some-> value str str/lower-case))

(defn- path-intent-query?
  [query]
  (let [q (lower-str query)]
    (boolean
     (or (str/starts-with? (or q "") "file:")
         (str/includes? (or q "") "/")
         (str/includes? (or q "") "\\")
         (re-find #"\.[a-z0-9]+$" (or q ""))))))

(defn- normalize-search-query
  [query]
  (let [q (clean-string query)]
    (if (and q (str/starts-with? q "file:"))
      (file-ref-id->path q)
      q)))

(defn- suggest-file-onboarding
  [db-value project-id entity-type-filter query matches]
  (let [normalized-query (normalize-search-query query)]
    (when (and (= :file entity-type-filter)
               (empty? matches)
               (path-intent-query? query)
               normalized-query)
      (let [suggested-file-id (project-scoped-file-id project-id normalized-query)
            legacy-file-id (str "file:" normalized-query)
            existing-file (or (store/pull-entity-by-id db-value suggested-file-id)
                              (store/pull-entity-by-id db-value legacy-file-id))
            common-suggestion {:suggested_file_id suggested-file-id
                               :suggested_action "upsert_code_entity"
                               :suggested_arguments {:kind "file"
                                                     :file_path normalized-query
                                                     :file_name (.getName (io/file normalized-query))}}]
        (if (= :file (:entity/type existing-file))
          (assoc common-suggestion
                 :existing_file_id (:entity/id existing-file)
                 :existing_file_project_id (get-in existing-file [:entity/project :entity/id]))
          common-suggestion)))))

(defn- file-fast-path-matches
  [db-value project-id query]
  (let [normalized-query (normalize-search-query query)
        expected-scoped-id (some-> normalized-query (project-scoped-file-id project-id))
        expected-legacy-id (some-> normalized-query (str "file:"))]
    (when (and normalized-query (not (str/blank? normalized-query)))
      (let [project-files (->> (store/project-entities db-value project-id)
                               (filter #(= :file (:entity/type %))))]
        (->> project-files
             (filter (fn [entity]
                       (or (= expected-scoped-id (:entity/id entity))
                           (= expected-legacy-id (:entity/id entity))
                           (= normalized-query (:entity/path entity))
                           (= normalized-query (:entity/id entity)))))
             store/distinct-summaries)))))

(defn- exact-code-query-score
  [query entity]
  (let [q (some-> query normalize-search-query lower-str)
        path-intent? (path-intent-query? query)
        entity-id (lower-str (:entity/id entity))
        entity-path (lower-str (:entity/path entity))
        entity-name (lower-str (:entity/name entity))
        qualified-name (lower-str (get entity :symbol/qualified-name))
        base-score (cond
                     (and q (or (= q entity-id) (= q entity-path) (= q entity-name) (= q qualified-name))) 100
                     (and q (or (and entity-path (str/includes? entity-path q))
                                (and entity-id (str/includes? entity-id q))
                                (and qualified-name (str/includes? qualified-name q)))) 60
                     (and q entity-name (str/includes? entity-name q)) 40
                     :else 0)
        file-bias (if (and path-intent?
                           (= :file (:entity/type entity))
                           (pos? base-score))
                    30
                    0)]
    (cond
      (and q
           path-intent?
           (= :file (:entity/type entity))
           (or (= q entity-path) (= q entity-id) (= q (some-> entity-id (str/replace #"^file:" "")))))
      (+ base-score 80)

      :else (+ base-score file-bias))))

(defn- ranked-search-results
  [query results]
  (->> results
       (map (fn [entity]
              [entity
               (exact-code-query-score query entity)
               (some-> (:entity/created-at entity) .getTime)]))
       (sort-by (fn [[entity score created-at]]
                  [score
                   (or created-at Long/MIN_VALUE)
                   (:entity/id entity)]))
       reverse
       (map first)
       vec))

(defn- same-run-scope?
  [run task-id session-id command]
  (and (= command (:tool-run/command run))
       (= task-id (get-in run [:entity/task :entity/id]))
       (= session-id (get-in run [:entity/session :entity/id]))))

(defn- infer-run-lineage
  [conn arguments]
  (let [provided-supersedes (vec (:supersedes_run_ids arguments))
        provided-retries (vec (:retries_of_run_ids arguments))]
    (if (or (seq provided-supersedes) (seq provided-retries))
      {:supersedes provided-supersedes
       :retries-of provided-retries
       :inferred false}
      (let [project-id (:project_id arguments)
            run-id (:run_id arguments)
            task-id (:task_id arguments)
            session-id (:session_id arguments)
            command (:command arguments)
            prior-runs (->> (store/entities-by-type (store/db conn) :tool-run project-id)
                            (remove #(= run-id (:entity/id %)))
                            (filter #(same-run-scope? % task-id session-id command))
                            (sort-by (fn [run]
                                       [(some-> (:entity/created-at run) .getTime)
                                        (:entity/id run)]))
                            reverse)
            prior-run-id (some-> prior-runs first :entity/id)]
        (if prior-run-id
          {:supersedes [prior-run-id]
           :retries-of [prior-run-id]
           :inferred true}
          {:supersedes []
           :retries-of []
           :inferred false})))))

(defn- upsert-code-entity!
  [conn arguments]
  (let [project-id (:project_id arguments)
        kind (util/->keyword (or (:kind arguments) (:entity_type arguments)))
        timestamp (or (:timestamp arguments) (util/now-iso))
        provenance (default-provenance arguments "upsert_code_entity")
        file-id (infer-file-id arguments)
        symbol-id (infer-symbol-id arguments)
        file-name (or (:file_name arguments)
                      (:module arguments)
                      file-id
                      "file")
        symbol-name (or (:symbol_name arguments)
                        (when-let [q (:qualified_name arguments)]
                          (last (str/split q #"/")))
                        symbol-id
                        "symbol")]
    (case kind
      :file
      (let [target-file-id (or file-id
                               (throw (ex-info "file_path or file_id is required for kind=file"
                                               {:kind kind})))
            file-entity (merge provenance
                               {:entity/id target-file-id
                                :entity/type :file
                                :entity/name file-name
                                :entity/path (:file_path arguments)
                                :entity/language (some-> (:language arguments) util/->keyword)
                                :entity/summary (:summary arguments)
                                :entity/body (or (:body arguments)
                                                 (:summary arguments)
                                                 (:file_path arguments)
                                                 (:module arguments))
                                :entity/project project-id
                                :entity/created-at timestamp
                                :entity/updated-at timestamp
                                :file/module (:module arguments)})]
        (store/transact-entities! conn [file-entity])
        {:kind "file"
         :entity (store/pull-entity-by-id (store/db conn) target-file-id)})

      :symbol
      (let [target-file-id (or file-id
                               (throw (ex-info "file_path or file_id is required for kind=symbol"
                                               {:kind kind})))
            target-symbol-id (or symbol-id
                                 (throw (ex-info "symbol_id or derivable symbol identity is required for kind=symbol"
                                                 {:kind kind})))
            file-entity (merge provenance
                               {:entity/id target-file-id
                                :entity/type :file
                                :entity/name file-name
                                :entity/path (:file_path arguments)
                                :entity/language (some-> (:language arguments) util/->keyword)
                                :entity/summary (:file_summary arguments)
                                :entity/body (or (:file_body arguments)
                                                 (:file_summary arguments)
                                                 (:file_path arguments))
                                :entity/project project-id
                                :entity/created-at timestamp
                                :entity/updated-at timestamp
                                :file/module (:module arguments)})
            symbol-entity (merge provenance
                                 {:entity/id target-symbol-id
                                  :entity/type :symbol
                                  :entity/name symbol-name
                                  :entity/summary (:summary arguments)
                                  :entity/body (or (:body arguments)
                                                   (:summary arguments)
                                                   (:qualified_name arguments)
                                                   symbol-name)
                                  :entity/project project-id
                                  :entity/file target-file-id
                                  :entity/created-at timestamp
                                  :entity/updated-at timestamp
                                  :symbol/file target-file-id
                                  :symbol/qualified-name (:qualified_name arguments)
                                  :symbol/signature (:signature arguments)
                                  :symbol/kind (some-> (:symbol_kind arguments) util/->keyword)})
            file-link-entity {:entity/id target-file-id
                              :file/contains-symbols [target-symbol-id]}]
        (store/transact-entities! conn [file-entity symbol-entity file-link-entity])
        {:kind "symbol"
         :symbol (store/pull-entity-by-id (store/db conn) target-symbol-id)
         :file (store/pull-entity-by-id (store/db conn) target-file-id)})

      (throw (ex-info "Unsupported code entity kind"
                      {:kind kind
                       :supported-kinds ["file" "symbol"]})))))

(defn- search-entities!
  [conn arguments]
  (let [project-id (:project_id arguments)
        query (:query arguments)
        limit (or (:limit arguments) 10)
        mode (util/->keyword (or (:mode arguments) "hybrid"))
        entity-type-filter (some-> (:entity_type arguments) util/->keyword)
        db-value (store/db conn)
        fast-path-file-results (when (and (= :file entity-type-filter)
                                          (path-intent-query? query))
                                 (file-fast-path-matches db-value project-id query))
        raw-results (case mode
                      :exact (queries/exact-lookup conn {:project-id project-id
                                                         :term query
                                                         :limit limit})
                      :fulltext (store/search-body db-value query {:limit limit
                                                                   :predicate #(= project-id (entity-project-id %))})
                      :hybrid (let [exact (queries/exact-lookup conn {:project-id project-id
                                                                      :term query
                                                                      :limit limit})
                                    fulltext (store/search-body db-value query {:limit limit
                                                                                :predicate #(= project-id (entity-project-id %))})]
                                (->> (concat exact fulltext)
                                     store/distinct-summaries))
                      (throw (ex-info "Unsupported search mode"
                                      {:mode mode
                                       :supported-modes ["exact" "fulltext" "hybrid"]})))
        maybe-type-filtered (cond->> raw-results
                               entity-type-filter (filter #(= entity-type-filter (:entity/type %))))
        base-ranked-results (ranked-search-results query maybe-type-filtered)
        ranked-results (if (seq fast-path-file-results)
                         (store/distinct-summaries (concat fast-path-file-results base-ranked-results))
                         base-ranked-results)
        relevant-results (if (and (= :file entity-type-filter)
                                  (path-intent-query? query))
                           (filter #(pos? (exact-code-query-score query %)) ranked-results)
                           ranked-results)
        filtered-results (->> relevant-results
                              (take limit))
        response {:project_id project-id
                  :query query
                  :mode (name mode)
                  :matches (mapv store/entity-brief filtered-results)}
        onboarding-suggestion (suggest-file-onboarding db-value project-id entity-type-filter query filtered-results)]
    (cond-> response
      onboarding-suggestion (merge onboarding-suggestion))))

(defn- remember-fact!
  [conn arguments]
  (let [attributes (deep-keywordize (or (:attributes arguments) {}))
        relationships (deep-keywordize (or (:relationships arguments) {}))
        provenance (default-provenance arguments "remember_fact")
        entity-id (:entity_id arguments)
        {:keys [entity-type entity-kind type-policy]} (normalize-entity-type (:entity_type arguments))
        timestamp (or (:timestamp arguments) (util/now-iso))
        external-refs (external-ref-values arguments attributes)
        raw-files (concat (util/ensure-vector (:files attributes))
                          (util/ensure-vector (:file_paths attributes)))
        file-ref-ids (->> raw-files
                          (map str)
                          (map str/trim)
                          (remove str/blank?)
                          (map #(normalize-file-ref-id (:project_id arguments) %))
                          distinct
                          vec)
        existing-refs (vec (util/ensure-vector (:entity/refs relationships)))
        merged-refs (->> (concat existing-refs file-ref-ids)
                         (remove nil?)
                         distinct
                         vec)
        cleaned-attributes (dissoc attributes :files :file_paths :external_refs)
        normalized-relationships (cond-> relationships
                                   (seq merged-refs) (assoc :entity/refs merged-refs))
        file-entities (mapv (fn [file-ref-id]
                              (let [path (file-ref-id->path file-ref-id)
                                    file-project-id (or (scoped-file-id-project-id file-ref-id)
                                                        (:project_id arguments))]
                                (merge provenance
                                       {:entity/id file-ref-id
                                        :entity/type :file
                                        :entity/name (.getName (io/file path))
                                        :entity/path path
                                        :entity/project file-project-id
                                        :entity/created-at timestamp
                                        :entity/updated-at timestamp
                                        :entity/body path})))
                            file-ref-ids)
        entity (merge cleaned-attributes
                      normalized-relationships
                      provenance
                      {:entity/id entity-id
                       :entity/type entity-type
                       :entity/kind (or (:entity/kind cleaned-attributes) entity-kind)
                       :entity/name (:name arguments)
                       :entity/summary (:summary arguments)
                       :entity/body (or (:entity/body cleaned-attributes)
                                        (:body arguments)
                                        (:summary arguments)
                                        (:name arguments))
                       :entity/project (:project_id arguments)
                       :entity/session (:session_id arguments)
                       :entity/task (:task_id arguments)
                       :entity/external-refs external-refs
                       :entity/created-at timestamp
                       :entity/updated-at timestamp})]
    (store/transact-entities! conn (conj file-entities entity))
    (let [result {:entity (store/pull-entity-by-id (store/db conn) entity-id)}]
    (assoc result
           :entity_type_policy type-policy
           :normalized_entity_type entity-type
           :normalized_file_refs file-ref-ids
           :normalized_external_refs external-refs))))

(defn- record-event!
  [conn arguments]
  (let [timestamp (or (:timestamp arguments) (util/now-iso))
        provenance (default-provenance arguments "record_event")
        subject-file-refs (->> (util/ensure-vector (:subject_file_paths arguments))
                               (map #(normalize-file-ref-id (:project_id arguments) %))
                               (remove nil?)
                               distinct
                               vec)
        subject-ids (->> (concat (util/ensure-vector (:subject_ids arguments))
                                 subject-file-refs)
                         (map clean-string)
                         (remove nil?)
                         distinct
                         vec)
        file-entities (->> subject-file-refs
                           (map #(file-entity-from-ref-id arguments provenance timestamp %))
                           (remove nil?)
                           vec)
        event-entity (merge provenance
                            {:entity/id (:event_id arguments)
                             :entity/type :event
                             :entity/name (or (:name arguments)
                                              (str "event " (:kind arguments)))
                             :entity/summary (:summary arguments)
                             :entity/body (or (:body arguments) (:summary arguments))
                             :entity/project (:project_id arguments)
                             :entity/session (:session_id arguments)
                             :entity/task (:task_id arguments)
                             :entity/created-at timestamp
                             :entity/updated-at timestamp
                             :event/kind (util/->keyword (:kind arguments))
                             :event/at timestamp
                             :event/session (:session_id arguments)
                             :event/subjects subject-ids})]
    (store/transact-entities! conn (conj file-entities event-entity))
    {:entity (store/pull-entity-by-id (store/db conn) (:event_id arguments))
     :normalized_subject_file_refs subject-file-refs}))

(defn- record-tool-run!
  [conn arguments]
  (when (compound-command? (:command arguments))
    (throw (ex-info "record_tool_run requires exactly one command"
                    {:command (:command arguments)
                     :validation_issue :compound_command
                     :guidance "Do not combine commands with &&, ||, ;, or |. Record each command as a separate tool run."})))
  (let [lineage (infer-run-lineage conn arguments)
        timestamp (or (:timestamp arguments) (util/now-iso))
        provenance (default-provenance arguments "record_tool_run")
        touched-file-refs (touched-file-ref-ids arguments)
        file-entities (->> touched-file-refs
                           (map #(file-entity-from-ref-id arguments provenance timestamp %))
                           (remove nil?)
                           vec)
        run-entity (merge provenance
                          {:entity/id (:run_id arguments)
                           :entity/type :tool-run
                           :entity/name (infer-run-name (:command arguments) (:name arguments))
                           :entity/status (run-status (:exit_code arguments))
                           :entity/summary (:summary arguments)
                           :entity/body (or (:body arguments) (:summary arguments) (:output arguments))
                           :entity/project (:project_id arguments)
                           :entity/session (:session_id arguments)
                           :entity/task (:task_id arguments)
                           :entity/created-at timestamp
                           :entity/updated-at timestamp
                           :tool-run/command (:command arguments)
                           :tool-run/phase (util/->keyword (or (:phase arguments) "tool"))
                           :tool-run/exit-code (long (or (:exit_code arguments) 0))
                           :tool-run/output (:output arguments)
                           :tool-run/touched-files touched-file-refs
                           :tool-run/supersedes (:supersedes lineage)
                           :tool-run/retries-of (:retries-of lineage)})]
    (store/transact-entities! conn (conj file-entities run-entity))
    {:entity (store/pull-entity-by-id (store/db conn) (:run_id arguments))
     :auto_lineage_inferred (:inferred lineage)
     :normalized_touched_file_refs touched-file-refs}))

(defn- record-error!
  [conn arguments]
  (let [timestamp (or (:timestamp arguments) (util/now-iso))
        provenance (default-provenance arguments "record_error")
        related-file-refs (->> (util/ensure-vector (:related_file_paths arguments))
                               (map #(normalize-file-ref-id (:project_id arguments) %))
                               (remove nil?)
                               distinct
                               vec)
        related-ids (->> (concat (util/ensure-vector (:related_symbol_ids arguments))
                                 (util/ensure-vector (:related_entity_ids arguments))
                                 related-file-refs)
                         (map clean-string)
                         (remove nil?)
                         distinct
                         vec)
        symbol-ids (vec (filter #(str/starts-with? % "symbol:") related-ids))
        file-entities (->> related-file-refs
                           (map #(file-entity-from-ref-id arguments provenance timestamp %))
                           (remove nil?)
                           vec)
        error-entity (merge provenance
                            {:entity/id (:error_id arguments)
                             :entity/type :error
                             :entity/name (or (:name arguments) "error")
                             :entity/status (util/->keyword (or (:status arguments) "open"))
                             :entity/summary (:summary arguments)
                             :entity/body (or (:body arguments) (:summary arguments) (:details arguments))
                             :entity/project (:project_id arguments)
                             :entity/session (:session_id arguments)
                             :entity/task (:task_id arguments)
                             :entity/refs related-ids
                             :entity/created-at timestamp
                             :entity/updated-at timestamp
                             :error/tool-run (:tool_run_id arguments)
                             :error/category (util/->keyword (or (:category arguments) "error"))
                             :error/details (:details arguments)
                             :error/related-symbols symbol-ids})]
    (store/transact-entities! conn (conj file-entities error-entity))
    {:entity (store/pull-entity-by-id (store/db conn) (:error_id arguments))
     :normalized_related_file_refs related-file-refs}))

(defn- link-entities!
  [conn arguments]
  (let [timestamp (or (:timestamp arguments) (util/now-iso))
        link-type (util/->keyword (:link_type arguments))
        link-entity (merge (default-provenance arguments "link_entities")
                           {:entity/id (:link_id arguments)
                            :entity/type :link
                            :entity/name (or (:name arguments) "entity link")
                            :entity/summary (:summary arguments)
                            :entity/body (or (:body arguments) (:summary arguments) (:explanation arguments))
                            :entity/project (:project_id arguments)
                            :entity/session (:session_id arguments)
                            :entity/task (:task_id arguments)
                            :entity/created-at timestamp
                            :entity/updated-at timestamp
                            :link/from (:from_id arguments)
                            :link/to (:to_id arguments)
                            :link/type link-type
                            :link/explanation (:explanation arguments)
                            :link/evidence (vec (:evidence_ids arguments))})
        maybe-resolution (when (= :resolved_by link-type)
                           {:entity/id (:from_id arguments)
                            :entity/status :resolved
                            :entity/updated-at timestamp
                            :entity/refs [(:to_id arguments)]})]
    (store/transact-entities! conn (if maybe-resolution
                                     [link-entity maybe-resolution]
                                     [link-entity]))
    {:entity (store/pull-entity-by-id (store/db conn) (:link_id arguments))
     :resolved_entity (when maybe-resolution
                        (store/pull-entity-by-id (store/db conn) (:from_id arguments)))}))

(defn- call-tool!
  [conn tool-name arguments]
  (validate-tool-arguments! tool-name arguments)
  (case tool-name
    "ensure_project" (ensure-project! conn arguments)
    "ensure_task" (ensure-task! conn arguments)
    "list_projects" (list-projects! conn)
    "upsert_code_entity" (upsert-code-entity! conn arguments)
    "search_entities" (search-entities! conn arguments)
    "remember_fact" (remember-fact! conn arguments)
    "record_event" (record-event! conn arguments)
    "record_tool_run" (record-tool-run! conn arguments)
    "record_error" (record-error! conn arguments)
    "link_entities" (link-entities! conn arguments)
    "search_notes" (queries/search-notes conn {:project-id (:project_id arguments)
                                               :project-ids (:project_ids arguments)
                                               :query (:query arguments)
                                               :limit (or (:limit arguments) 5)})
    "find_related_context" (queries/find-related-context conn {:entity-id (:entity_id arguments)
                                                               :project-ids (:project_ids arguments)
                                                               :limit (or (:limit arguments) 8)
                                                               :hops (or (:hops arguments) 2)})
    "get_symbol_memory" (queries/get-symbol-memory conn {:symbol-id (:symbol_id arguments)
                                                         :limit (or (:limit arguments) 12)})
    "get_task_timeline" (queries/get-task-timeline conn {:task-id (:task_id arguments)})
    "summarize_project_memory" (queries/summarize-project-memory conn {:project-id (:project_id arguments)})
    "normalize_project_memory" (maintenance/normalize-project-memory! conn arguments)
    (throw (ex-info "Unknown tool" {:tool-name tool-name}))))

(defn- with-project-remediation
  [data]
  (cond
    (= :entity/project (:attr data))
    (assoc data
           :hint "Create or ensure this project first, then retry the write."
           :suggested_tool "ensure_project"
           :suggested_args {:project_id (:ref-id data)})

    (contains? data :existing-project-id)
    (assoc data
           :hint "Task id already exists under a different project. Use a unique task_id or correct project_id."
           :suggested_tool "ensure_task"
           :suggested_args {:project_id (:project-id data)
                            :task_id (str (or (:task-id data) "task:new-id"))})

    (= :entity/task (:attr data))
    (assoc data
           :hint "Create or ensure this task first, then retry the write."
           :suggested_tool "ensure_task"
           :suggested_args {:project_id (or (:project-id data) (:request-project-id data))
                            :task_id (:ref-id data)})

    (:project-id data)
    (assoc data
           :hint "This project was not found. List known projects or create one."
           :suggested_tools ["list_projects" "ensure_project"]
           :suggested_args {:project_id (:project-id data)})

    (and (= :entity/refs (:attr data))
         (string? (:ref-id data))
         (str/starts-with? (:ref-id data) "symbol:"))
    (assoc data
           :hint "Referenced symbol does not exist yet. Create/discover symbol ids before linking."
           :suggested_tools ["search_entities" "upsert_code_entity"]
           :suggested_args {:project_id (or (:project-id data) (:request-project-id data))
                            :query (:ref-id data)
                            :mode "exact"})

    :else data))

(defn- classify-tool-error
  [message data]
  (let [error-type
        (cond
          (contains? data :missing_required_arguments) "validation"
          (contains? data :unsupported_arguments) "validation"
          (= :compound_command (:validation_issue data)) "validation"
          (= "Project does not exist" message) "bootstrap_missing_project"
          (= :entity/project (:attr data)) "bootstrap_missing_project"
          (and (:project-id data)
               (not (:tool_name data))
               (not (:attr data))) "bootstrap_missing_project"
          (= :entity/task (:attr data)) "bootstrap_missing_task"
          (contains? data :existing-project-id) "conflict"
          (= "Unknown tool" message) "unknown_tool"
          :else "runtime")
        retry-bootstrap? (contains? #{"bootstrap_missing_project" "bootstrap_missing_task"} error-type)]
    (assoc data
           :error_type error-type
           :retry_bootstrap_recommended retry-bootstrap?)))

(defn- tools
   []
   [{:name "ensure_project"
    :description "Use before first write in a repo to create or verify a project memory root."
    :inputSchema {:type "object"
                  :properties {:project_id {:type "string"}
                               :name {:type "string"}
                               :summary {:type "string"}
                               :body {:type "string"}
                               :root {:type "string"}
                               :timestamp {:type "string"}
                               :provenance {:type "object"
                                            :properties {:source {:type "string"}
                                                         :source_ref {:type "string"}}}}
                  :required ["project_id"]}}
   {:name "ensure_task"
    :description "Use before writing task-scoped runs/events/errors to create or verify the task entity in a project."
    :inputSchema {:type "object"
                  :properties {:project_id {:type "string"}
                               :task_id {:type "string"}
                               :name {:type "string"}
                               :summary {:type "string"}
                               :body {:type "string"}
                               :description {:type "string"}
                               :status {:type "string"}
                               :priority {:type "string"}
                               :session_id {:type "string"}
                               :timestamp {:type "string"}
                               :provenance {:type "object"
                                            :properties {:source {:type "string"}
                                                         :source_ref {:type "string"}}}}
                  :required ["project_id" "task_id"]}}
   {:name "list_projects"
    :description "Use when project_id is unknown to discover available project memory roots."
    :inputSchema {:type "object"
                  :properties {}
                  :additionalProperties false}}
   {:name "upsert_code_entity"
    :description "Use to create or update file/symbol entities on demand in non-seeded repositories so graph retrieval can anchor on real code entities."
    :inputSchema {:type "object"
                  :properties {:project_id {:type "string"}
                               :kind {:type "string"}
                               :file_id {:type "string"}
                               :file_path {:type "string"}
                               :file_name {:type "string"}
                               :module {:type "string"}
                               :language {:type "string"}
                               :symbol_id {:type "string"}
                               :symbol_name {:type "string"}
                               :qualified_name {:type "string"}
                               :signature {:type "string"}
                               :symbol_kind {:type "string"}
                               :summary {:type "string"}
                               :body {:type "string"}
                               :file_summary {:type "string"}
                               :file_body {:type "string"}
                               :timestamp {:type "string"}
                               :provenance {:type "object"
                                            :properties {:source {:type "string"}
                                                         :source_ref {:type "string"}}}}
                  :required ["project_id" "kind"]}}
   {:name "search_entities"
    :description "Use to discover entity ids (especially symbols/files) before linking errors, observations, or decisions."
    :inputSchema {:type "object"
                  :properties {:project_id {:type "string"}
                               :query {:type "string"}
                               :mode {:type "string"}
                               :entity_type {:type "string"}
                               :limit {:type "integer"}}
                  :required ["project_id" "query"]}}
   {:name "remember_fact"
    :description "Use when you need to persist a new or updated structured memory fact with provenance and explicit relationships. If attributes include files/file_paths, the server auto-upserts file:* entities and links them via entity/refs. External refs can be passed in external_refs or attributes.external_refs."
    :inputSchema {:type "object"
                  :properties {:entity_id {:type "string"}
                               :entity_type {:type "string"}
                               :name {:type "string"}
                               :summary {:type "string"}
                               :body {:type "string"}
                               :project_id {:type "string"}
                               :session_id {:type "string"}
                               :task_id {:type "string"}
                               :timestamp {:type "string"}
                               :attributes {:type "object"
                                            :additionalProperties true}
                               :relationships {:type "object"
                                               :additionalProperties true}
                               :external_refs {:type "array"
                                               :items {:type ["string" "object"]}}
                               :provenance {:type "object"
                                            :properties {:source {:type "string"}
                                                         :source_ref {:type "string"}}}}
                  :required ["entity_id" "entity_type"]}}
   {:name "record_event"
    :description "Use when an important step happened and you want it on the session/task timeline (for example failure detected, patch landed, follow-up logged)."
    :inputSchema {:type "object"
                  :properties {:event_id {:type "string"}
                               :kind {:type "string"}
                               :name {:type "string"}
                               :summary {:type "string"}
                               :project_id {:type "string"}
                               :session_id {:type "string"}
                               :task_id {:type "string"}
                               :timestamp {:type "string"}
                               :subject_ids {:type "array" :items {:type "string"}}
                               :subject_file_paths {:type "array" :items {:type "string"}}
                               :provenance {:type "object"
                                            :properties {:source {:type "string"}
                                                         :source_ref {:type "string"}}}}
                  :required ["event_id" "kind" "project_id"]}}
   {:name "record_tool_run"
    :description "Use immediately after running tests/build/lint/commands to persist command, output, exit code, touched files, and provenance."
    :inputSchema {:type "object"
                  :properties {:run_id {:type "string"}
                               :name {:type "string"}
                               :summary {:type "string"}
                               :project_id {:type "string"}
                               :session_id {:type "string"}
                               :task_id {:type "string"}
                               :command {:type "string"}
                               :phase {:type "string"}
                               :exit_code {:type "integer"}
                               :output {:type "string"}
                               :touched_file_ids {:type "array" :items {:type "string"}}
                               :touched_file_paths {:type "array" :items {:type "string"}}
                               :supersedes_run_ids {:type "array" :items {:type "string"}}
                               :retries_of_run_ids {:type "array" :items {:type "string"}}
                               :timestamp {:type "string"}
                               :provenance {:type "object"
                                            :properties {:source {:type "string"}
                                                         :source_ref {:type "string"}}}}
                  :required ["run_id" "project_id" "command"]}}
   {:name "record_error"
    :description "Use when a command or test fails and you need to store the failure with originating tool run, related symbols, and supporting references."
    :inputSchema {:type "object"
                  :properties {:error_id {:type "string"}
                               :name {:type "string"}
                               :summary {:type "string"}
                               :details {:type "string"}
                               :project_id {:type "string"}
                               :session_id {:type "string"}
                               :task_id {:type "string"}
                               :tool_run_id {:type "string"}
                               :category {:type "string"}
                               :status {:type "string"}
                               :related_symbol_ids {:type "array" :items {:type "string"}}
                               :related_entity_ids {:type "array" :items {:type "string"}}
                               :related_file_paths {:type "array" :items {:type "string"}}
                               :timestamp {:type "string"}
                               :provenance {:type "object"
                                            :properties {:source {:type "string"}
                                                         :source_ref {:type "string"}}}}
                  :required ["error_id" "project_id" "summary"]}}
   {:name "link_entities"
    :description "Use when a causal or supporting relationship should be explicit (for example decision justifies patch, error motivated decision, or project dependency links like depends_on/calls_api)."
    :inputSchema {:type "object"
                  :properties {:link_id {:type "string"}
                               :name {:type "string"}
                               :summary {:type "string"}
                               :project_id {:type "string"}
                               :session_id {:type "string"}
                               :task_id {:type "string"}
                               :from_id {:type "string"}
                               :to_id {:type "string"}
                               :link_type {:type "string"}
                               :explanation {:type "string"}
                               :evidence_ids {:type "array" :items {:type "string"}}
                               :timestamp {:type "string"}
                               :provenance {:type "object"
                                            :properties {:source {:type "string"}
                                                         :source_ref {:type "string"}}}}
                  :required ["link_id" "project_id" "from_id" "to_id" "link_type"]}}
   {:name "search_notes"
    :description "Use for broad recall when you have a text query and want note hits within one or more projects."
    :inputSchema {:type "object"
                  :properties {:project_id {:type "string"}
                               :project_ids {:type "array" :items {:type "string"}}
                               :query {:type "string"}
                               :limit {:type "integer"}}
                  :required ["query"]}}
   {:name "find_related_context"
    :description "Use before editing or debugging an entity to load nearby graph context (tasks, errors, decisions, patches, notes). project_ids can broaden traversal across related projects."
    :inputSchema {:type "object"
                  :properties {:entity_id {:type "string"}
                               :project_ids {:type "array" :items {:type "string"}}
                               :limit {:type "integer"}
                               :hops {:type "integer"}}
                  :required ["entity_id"]}}
   {:name "get_symbol_memory"
    :description "Use when working on a specific symbol and you need its dossier: related failures, patches, notes, decisions, and timeline signals."
    :inputSchema {:type "object"
                  :properties {:symbol_id {:type "string"}
                               :limit {:type "integer"}}
                  :required ["symbol_id"]}}
   {:name "get_task_timeline"
    :description "Use when you need the ordered story of a task from failure to analysis to decision to patch to follow-up."
    :inputSchema {:type "object"
                  :properties {:task_id {:type "string"}}
                  :required ["task_id"]}}
   {:name "summarize_project_memory"
    :description "Use at session start to bootstrap context: project counts, recent patches/failures, and active tasks."
    :inputSchema {:type "object"
                  :properties {:project_id {:type "string"}}
                  :required ["project_id"]}}
   {:name "normalize_project_memory"
    :description "Admin maintenance tool. Use in dry_run first, then apply to normalize legacy types, backfill resolved failures, and link run supersession metadata."
    :inputSchema {:type "object"
                  :properties {:project_id {:type "string"}
                               :mode {:type "string"}
                               :operations {:type "array" :items {:type "string"}}
                               :max_changes {:type "integer"}
                               :migration_id {:type "string"}
                               :provenance {:type "object"
                                            :properties {:source {:type "string"}
                                                         :source_ref {:type "string"}}}}
                  :required ["project_id" "mode"]}}])

(defn- resource
  [uri name description]
  {:uri uri
   :name name
   :description description
   :mimeType "application/json"})

(defn- resources-list
  [conn]
  (let [db-value (store/db conn)
        project-ids (store/all-project-ids db-value)
        session-ids (map :entity/id (store/entities-by-type db-value :session nil))
        task-ids (map :entity/id (store/entities-by-type db-value :task nil))]
    (vec
     (concat
      (mapcat (fn [project-id]
                [(resource (str "memory://project/" project-id "/summary")
                           (str project-id " summary")
                           "Project summary with counts and recent activity.")
                 (resource (str "memory://project/" project-id "/recent-failures")
                           (str project-id " recent failures")
                           "Recent project failures.")
                 (resource (str "memory://project/" project-id "/active-tasks")
                           (str project-id " active tasks")
                           "Active tasks in the project.")])
              project-ids)
      (map (fn [session-id]
             (resource (str "memory://session/" session-id "/timeline")
                       (str session-id " timeline")
                       "Session-scoped timeline."))
           session-ids)
      (map (fn [task-id]
             (resource (str "memory://task/" task-id "/timeline")
                       (str task-id " timeline")
                       "Task timeline."))
           task-ids)))))

(defn- resource-templates
  []
  [{:uriTemplate "memory://symbol/{symbol_id}/dossier"
    :name "Symbol dossier"
    :description "Read the symbol dossier for a symbol entity id."
    :mimeType "application/json"}
   {:uriTemplate "memory://project/{project_id}/summary"
    :name "Project summary"
    :description "Read the project summary for a project entity id."
    :mimeType "application/json"}])

(defn- read-resource
  [conn uri]
  (let [payload
        (cond
          (re-matches #"memory://project/(.+)/summary" uri)
          (let [[_ project-id] (re-matches #"memory://project/(.+)/summary" uri)]
            (queries/project-summary conn project-id))

          (re-matches #"memory://project/(.+)/recent-failures" uri)
          (let [[_ project-id] (re-matches #"memory://project/(.+)/recent-failures" uri)]
            {:project_id project-id
             :recent_failures (queries/recent-failures conn project-id)})

          (re-matches #"memory://project/(.+)/active-tasks" uri)
          (let [[_ project-id] (re-matches #"memory://project/(.+)/active-tasks" uri)]
            {:project_id project-id
             :active_tasks (queries/active-tasks conn project-id)})

          (re-matches #"memory://session/(.+)/timeline" uri)
          (let [[_ session-id] (re-matches #"memory://session/(.+)/timeline" uri)]
            (queries/session-timeline conn session-id))

          (re-matches #"memory://task/(.+)/timeline" uri)
          (let [[_ task-id] (re-matches #"memory://task/(.+)/timeline" uri)]
            (queries/get-task-timeline conn {:task-id task-id}))

          (re-matches #"memory://symbol/(.+)/dossier" uri)
          (let [[_ symbol-id] (re-matches #"memory://symbol/(.+)/dossier" uri)]
            (queries/get-symbol-memory conn {:symbol-id symbol-id}))

          :else
          (throw (ex-info "Unknown resource URI" {:uri uri})))]
    {:contents [{:uri uri
                 :mimeType "application/json"
                 :text (json/write-str (util/json-friendly payload))}]}))

(defn- handle-request
  [conn initialized? {:keys [id method params] :as request}]
  (try
    (case method
      "initialize"
      [(response id {:protocolVersion protocol-version
                     :capabilities {:tools {:listChanged false}
                                    :resources {:listChanged false}}
                     :serverInfo {:name "datalaga"
                                  :version "0.1.0"}
                     :instructions "Structured coding-memory MCP server backed by Datalevin."})
       (reset! initialized? true)]

      "notifications/initialized"
      [nil (reset! initialized? true)]

      "ping"
      [(response id {}) nil]

      "tools/list"
      [(response id {:tools (tools)}) nil]

      "tools/call"
      (let [arguments (deep-keywordize (or (:arguments params) {}))
            result (call-tool! conn (:name params) arguments)]
        [(response id (tool-result result)) nil])

      "resources/list"
      [(response id {:resources (resources-list conn)}) nil]

      "resources/templates/list"
      [(response id {:resourceTemplates (resource-templates)}) nil]

      "resources/read"
      [(response id (read-resource conn (:uri params))) nil]

      "notifications/cancelled"
      [nil nil]

      [(error-response id -32601 "Method not found" {:method method
                                                     :request request})
       nil])
    (catch Exception ex
      [(response id (tool-error (.getMessage ex)
                                (-> (merge (ex-data ex)
                                           {:tool_name (:name params)
                                            :request-project-id (or (get-in params [:arguments :project_id])
                                                                    (get-in params [:arguments "project_id"]))}
                                           {:method method})
                                    with-project-remediation
                                    (#(classify-tool-error (.getMessage ex) %)))))
       nil])))

(defn- run-server!
  [{:keys [db-path seed-file]}]
  (ingest/seed! {:db-path db-path :seed-file seed-file})
  (let [conn (store/open-conn db-path)
        initialized? (atom false)
        reader (io/reader *in*)
        writer (io/writer *out*)]
    (try
      (doseq [line (line-seq reader)]
        (when-not (str/blank? line)
          (let [request (json/read-str line :key-fn keyword)
                [reply _] (handle-request conn initialized? request)]
            (when reply
              (write-message! writer reply)))))
      (finally
        (store/close! conn)))))

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
      (run-server! options))))
