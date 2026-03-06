(ns datalevin-mcp.mcp.server
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [datalevin-mcp.ingest :as ingest]
            [datalevin-mcp.memory.queries :as queries]
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

(defn- remember-fact!
  [conn arguments]
  (let [attributes (deep-keywordize (or (:attributes arguments) {}))
        relationships (deep-keywordize (or (:relationships arguments) {}))
        provenance (default-provenance arguments "remember_fact")
        entity-id (:entity_id arguments)
        entity (merge attributes
                      relationships
                      provenance
                      {:entity/id entity-id
                       :entity/type (util/->keyword (:entity_type arguments))
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
                       :entity/updated-at (or (:timestamp arguments) (util/now-iso))})]
    (transact-and-fetch! conn entity)))

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
           :entity/name (or (:name arguments) "tool run")
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
           :tool-run/touched-files (vec (:touched_file_ids arguments))})))

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
  (transact-and-fetch!
   conn
   (merge (default-provenance arguments "link_entities")
          {:entity/id (:link_id arguments)
           :entity/type :link
           :entity/name (or (:name arguments) "entity link")
           :entity/summary (:summary arguments)
           :entity/body (or (:body arguments) (:summary arguments) (:explanation arguments))
           :entity/project (:project_id arguments)
           :entity/session (:session_id arguments)
           :entity/task (:task_id arguments)
           :entity/created-at (or (:timestamp arguments) (util/now-iso))
           :entity/updated-at (or (:timestamp arguments) (util/now-iso))
           :link/from (:from_id arguments)
           :link/to (:to_id arguments)
           :link/type (util/->keyword (:link_type arguments))
           :link/explanation (:explanation arguments)
           :link/evidence (vec (:evidence_ids arguments))})))

(defn- call-tool!
  [conn tool-name arguments]
  (case tool-name
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

(defn- tools
   []
   [{:name "remember_fact"
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
                                (merge (ex-data ex)
                                       {:method method})))
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
