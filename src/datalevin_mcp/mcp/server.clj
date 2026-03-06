(ns datalevin-mcp.mcp.server
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [datalevin-mcp.ingest :as ingest]
            [datalevin-mcp.memory.queries :as queries]
            [datalevin-mcp.memory.schema :as memory-schema]
            [datalevin-mcp.memory.store :as store]
            [datalevin-mcp.util :as util]))

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

(defn- list-projects!
  [conn]
  (let [db-value (store/db conn)]
    {:projects (->> (store/all-project-ids db-value)
                    (store/pull-entities-by-id db-value)
                    (sort-by :entity/id)
                    (mapv store/entity-brief))}))

(defn- entity-project-id
  [entity]
  (or (get-in entity [:entity/project :entity/id])
      (when (= :project (:entity/type entity))
        (:entity/id entity))))

(defn- infer-file-id
  [{:keys [file_id file_path]}]
  (or file_id
      (when file_path
        (str "file:" file_path))))

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
        filtered-results (cond->> raw-results
                           entity-type-filter (filter #(= entity-type-filter (:entity/type %)))
                           true (take limit))]
    {:project_id project-id
     :query query
     :mode (name mode)
     :matches (mapv store/entity-brief filtered-results)}))

(defn- remember-fact!
  [conn arguments]
  (let [attributes (deep-keywordize (or (:attributes arguments) {}))
        relationships (deep-keywordize (or (:relationships arguments) {}))
        provenance (default-provenance arguments "remember_fact")
        entity-id (:entity_id arguments)
        {:keys [entity-type entity-kind type-policy]} (normalize-entity-type (:entity_type arguments))
        entity (merge attributes
                      relationships
                      provenance
                      {:entity/id entity-id
                       :entity/type entity-type
                       :entity/kind (or (:entity/kind attributes) entity-kind)
                       :entity/name (:name arguments)
                       :entity/summary (:summary arguments)
                       :entity/body (or (:entity/body attributes)
                                        (:body arguments)
                                        (:summary arguments)
                                        (:name arguments))
                       :entity/project (:project_id arguments)
                       :entity/session (:session_id arguments)
                       :entity/task (:task_id arguments)
                       :entity/created-at (or (:timestamp arguments) (util/now-iso))
                       :entity/updated-at (or (:timestamp arguments) (util/now-iso))})
        result (transact-and-fetch! conn entity)]
    (assoc result
           :entity_type_policy type-policy
           :normalized_entity_type entity-type)))

(defn- record-event!
  [conn arguments]
  (transact-and-fetch!
   conn
   (merge (default-provenance arguments "record_event")
          {:entity/id (:event_id arguments)
           :entity/type :event
           :entity/name (or (:name arguments)
                            (str "event " (:kind arguments)))
           :entity/summary (:summary arguments)
           :entity/body (or (:body arguments) (:summary arguments))
           :entity/project (:project_id arguments)
           :entity/session (:session_id arguments)
           :entity/task (:task_id arguments)
           :entity/created-at (or (:timestamp arguments) (util/now-iso))
           :entity/updated-at (or (:timestamp arguments) (util/now-iso))
           :event/kind (util/->keyword (:kind arguments))
           :event/at (or (:timestamp arguments) (util/now-iso))
           :event/session (:session_id arguments)
           :event/subjects (vec (:subject_ids arguments))})))

(defn- record-tool-run!
  [conn arguments]
  (transact-and-fetch!
   conn
   (merge (default-provenance arguments "record_tool_run")
          {:entity/id (:run_id arguments)
           :entity/type :tool-run
           :entity/name (infer-run-name (:command arguments) (:name arguments))
           :entity/status (run-status (:exit_code arguments))
           :entity/summary (:summary arguments)
           :entity/body (or (:body arguments) (:summary arguments) (:output arguments))
           :entity/project (:project_id arguments)
           :entity/session (:session_id arguments)
           :entity/task (:task_id arguments)
           :entity/created-at (or (:timestamp arguments) (util/now-iso))
           :entity/updated-at (or (:timestamp arguments) (util/now-iso))
           :tool-run/command (:command arguments)
           :tool-run/phase (util/->keyword (or (:phase arguments) "tool"))
           :tool-run/exit-code (long (or (:exit_code arguments) 0))
           :tool-run/output (:output arguments)
           :tool-run/touched-files (vec (:touched_file_ids arguments))
           :tool-run/supersedes (vec (:supersedes_run_ids arguments))
           :tool-run/retries-of (vec (:retries_of_run_ids arguments))})))

(defn- record-error!
  [conn arguments]
  (let [related-ids (vec (distinct (concat (:related_symbol_ids arguments)
                                           (:related_entity_ids arguments))))
        symbol-ids (vec (filter #(str/starts-with? % "symbol:") related-ids))]
    (transact-and-fetch!
     conn
     (merge (default-provenance arguments "record_error")
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
             :entity/created-at (or (:timestamp arguments) (util/now-iso))
             :entity/updated-at (or (:timestamp arguments) (util/now-iso))
             :error/tool-run (:tool_run_id arguments)
             :error/category (util/->keyword (or (:category arguments) "error"))
             :error/details (:details arguments)
             :error/related-symbols symbol-ids}))))

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
  (case tool-name
    "ensure_project" (ensure-project! conn arguments)
    "list_projects" (list-projects! conn)
    "upsert_code_entity" (upsert-code-entity! conn arguments)
    "search_entities" (search-entities! conn arguments)
    "remember_fact" (remember-fact! conn arguments)
    "record_event" (record-event! conn arguments)
    "record_tool_run" (record-tool-run! conn arguments)
    "record_error" (record-error! conn arguments)
    "link_entities" (link-entities! conn arguments)
    "search_notes" (queries/search-notes conn {:project-id (:project_id arguments)
                                               :query (:query arguments)
                                               :limit (or (:limit arguments) 5)})
    "find_related_context" (queries/find-related-context conn {:entity-id (:entity_id arguments)
                                                               :limit (or (:limit arguments) 8)
                                                               :hops (or (:hops arguments) 2)})
    "get_symbol_memory" (queries/get-symbol-memory conn {:symbol-id (:symbol_id arguments)
                                                         :limit (or (:limit arguments) 12)})
    "get_task_timeline" (queries/get-task-timeline conn {:task-id (:task_id arguments)})
    "summarize_project_memory" (queries/summarize-project-memory conn {:project-id (:project_id arguments)})
    (throw (ex-info "Unknown tool" {:tool-name tool-name}))))

(defn- with-project-remediation
  [data]
  (cond
    (= :entity/project (:attr data))
    (assoc data
           :hint "Create or ensure this project first, then retry the write."
           :suggested_tool "ensure_project"
           :suggested_args {:project_id (:ref-id data)})

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
           :suggested_args {:project_id (:project-id data)
                            :query (:ref-id data)
                            :mode "exact"})

    :else data))

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
    :description "Use when you need to persist a new or updated structured memory fact with provenance and explicit relationships."
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
                               :timestamp {:type "string"}
                               :provenance {:type "object"
                                            :properties {:source {:type "string"}
                                                         :source_ref {:type "string"}}}}
                  :required ["error_id" "project_id" "summary"]}}
   {:name "link_entities"
    :description "Use when a causal or supporting relationship should be explicit (for example decision justifies patch, error motivated decision)."
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
    :description "Use for broad recall when you have a text query and want note hits within a project."
    :inputSchema {:type "object"
                  :properties {:project_id {:type "string"}
                               :query {:type "string"}
                               :limit {:type "integer"}}
                  :required ["project_id" "query"]}}
   {:name "find_related_context"
    :description "Use before editing or debugging an entity to load nearby graph context (tasks, errors, decisions, patches, notes)."
    :inputSchema {:type "object"
                  :properties {:entity_id {:type "string"}
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
                  :required ["project_id"]}}])

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
                     :serverInfo {:name "datalevin-mcp"
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
                                (with-project-remediation
                                  (merge (ex-data ex)
                                         {:method method}))))
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
