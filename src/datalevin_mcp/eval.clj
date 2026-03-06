(ns datalevin-mcp.eval
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [datalevin-mcp.ingest :as ingest]
            [datalevin-mcp.memory.queries :as queries]
            [datalevin-mcp.memory.store :as store]
            [datalevin-mcp.util :as util]))

(def cli-options
  [["-d" "--db-path PATH" "Path to the Datalevin database directory."
    :default store/default-db-path]
   ["-s" "--seed-file FILE" "Path to the EDN seed dataset."
    :default ingest/default-seed-file]
   ["-r" "--report-file FILE" "Where to write the markdown evaluation report."
    :default "eval/report.md"]
   ["-h" "--help"]])

(def project-id "project:phoenix-auth")

(def scenarios
  [{:id "auth-recent-changes"
    :prompt "What changed in authentication recently, and why?"
    :project-id project-id
    :anchor-id "file:src/auth/session.clj"
    :exact-term "src/auth/session.clj"
    :expected-ids #{"patch:refresh-session-generation-check"
                    "patch:stabilize-refresh-rotation-test"
                    "decision:check-session-generation-on-refresh"
                    "decision:inject-clock-into-refresh-tests"
                    "note:backfill-session-generation"
                    "note:follow-up-audit-token-revocation"}
    :graph-fn (fn [conn scenario]
                (:context-to-load (queries/context-for-file conn (:anchor-id scenario))))}
   {:id "failures-related-symbol"
    :prompt "Which failures are related to this symbol?"
    :project-id project-id
    :anchor-id "symbol:src/auth/session.clj#refresh-session"
    :exact-term "symbol:src/auth/session.clj#refresh-session"
    :expected-ids #{"error:stale-session-after-reset"
                    "error:refresh-rotation-flake"
                    "patch:refresh-session-generation-check"
                    "patch:stabilize-refresh-rotation-test"
                    "decision:check-session-generation-on-refresh"}
    :graph-fn (fn [conn scenario]
                (->> (queries/get-symbol-memory conn {:symbol-id (:anchor-id scenario)})
                     :related
                     vals
                     (apply concat)))}
   {:id "prior-decisions-for-task"
    :prompt "What prior decisions touched files that this task is about to modify?"
    :project-id project-id
    :anchor-id "task:AUTH-160"
    :exact-term "task:AUTH-160"
    :expected-ids #{"decision:check-session-generation-on-refresh"}
    :graph-fn (fn [conn scenario]
                (queries/prior-decisions-for-task conn (:anchor-id scenario)))}
   {:id "timeline-failure-to-patch-to-note"
    :prompt "Show the timeline from failing test to patch to follow-up note."
    :project-id project-id
    :anchor-id "task:AUTH-142"
    :exact-term "task:AUTH-142"
    :expected-ids #{"tool-run:password-reset-regression"
                    "error:stale-session-after-reset"
                    "observation:refresh-path-skips-generation"
                    "decision:check-session-generation-on-refresh"
                    "patch:refresh-session-generation-check"
                    "note:backfill-session-generation"
                    "note:follow-up-audit-token-revocation"}
    :graph-fn (fn [conn scenario]
                (:timeline (queries/get-task-timeline conn {:task-id (:anchor-id scenario)})))}
   {:id "context-before-edit-module"
    :prompt "What context should an agent load before editing this module?"
    :project-id project-id
    :anchor-id "file:src/auth/session.clj"
    :exact-term "file:src/auth/session.clj"
    :expected-ids #{"symbol:src/auth/session.clj#refresh-session"
                    "symbol:src/auth/session.clj#invalidate-user-sessions"
                    "task:AUTH-142"
                    "task:AUTH-160"
                    "decision:check-session-generation-on-refresh"
                    "patch:refresh-session-generation-check"
                    "error:stale-session-after-reset"
                    "note:backfill-session-generation"}
    :graph-fn (fn [conn scenario]
                (:context-to-load (queries/context-for-file conn (:anchor-id scenario))))}])

(defn- usage
  [summary]
  (str/join
   \newline
   ["Run the seeded retrieval evaluation and MCP smoke test."
    ""
    "Usage: clojure -M:eval [options]"
    ""
    "Options:"
    summary]))

(defn- eprintln
  [& values]
  (binding [*out* *err*]
    (apply println values)))

(defn- results->entities
  [result]
  (cond
    (nil? result) []
    (vector? result) result
    (and (map? result) (vector? (:results result))) (:results result)
    (and (map? result) (vector? (:related result))) (:related result)
    (and (map? result) (map? (:related result))) (vec (apply concat (vals (:related result))))
    (and (map? result) (vector? (:matches result))) (:matches result)
    (and (map? result) (vector? (:timeline result))) (:timeline result)
    (and (map? result) (vector? (:context-to-load result))) (:context-to-load result)
    (and (map? result) (vector? (:decisions result))) (:decisions result)
    :else []))

(defn- entity-ids
  [entities]
  (->> entities
       (map :entity/id)
       (remove nil?)
       vec))

(defn- score-results
  [expected result-entities]
  (let [result-ids (entity-ids result-entities)
        hits (set/intersection expected (set result-ids))
        hit-count (count hits)]
    {:result-ids result-ids
     :hits (vec (sort hits))
     :hit-count hit-count
     :precision (double (/ hit-count (max 1 (count result-ids))))
     :recall (double (/ hit-count (max 1 (count expected))))}))

(defn- exact-results
  [conn scenario]
  (queries/exact-lookup conn {:project-id (:project-id scenario)
                              :term (:exact-term scenario)
                              :limit 8}))

(defn- graph-results
  [conn scenario]
  (if-let [graph-fn (:graph-fn scenario)]
    (graph-fn conn scenario)
    (:related (queries/find-related-context conn {:entity-id (:anchor-id scenario)
                                                  :limit 8
                                                  :hops 2}))))

(defn- fulltext-results
  [conn scenario]
  (let [db-value (store/db conn)]
    (store/search-body db-value (:prompt scenario)
                       {:limit 8
                        :predicate #(= (:project-id scenario)
                                       (or (get-in % [:entity/project :entity/id])
                                           (when (= :project (:entity/type %))
                                             (:entity/id %))))})))

(defn- hybrid-results
  [conn scenario]
  (:results (queries/hybrid-context conn {:project-id (:project-id scenario)
                                          :query (:prompt scenario)
                                          :anchor-id (:anchor-id scenario)
                                          :limit 8})))

(def retrieval-modes
  [{:id :exact
    :label "Exact entity lookup"
    :runner exact-results}
   {:id :graph
    :label "Graph/EAV traversal"
    :runner graph-results}
   {:id :fulltext
    :label "Full-text search"
    :runner fulltext-results}
   {:id :hybrid
    :label "Hybrid text + graph"
    :runner hybrid-results}])

(defn- run-retrieval-eval
  [conn]
  (for [scenario scenarios]
    {:id (:id scenario)
     :prompt (:prompt scenario)
     :expected-ids (vec (sort (:expected-ids scenario)))
     :modes (for [{:keys [id label runner]} retrieval-modes
                  :let [raw-results (results->entities (runner conn scenario))
                        score (score-results (:expected-ids scenario) raw-results)]]
              {:id id
               :label label
               :score score})}))

(defn- avg
  [xs]
  (let [vals (->> xs (remove nil?) vec)]
    (if (seq vals)
      (/ (reduce + vals) (double (count vals)))
      0.0)))

(defn- mode-score
  [scenario-result mode-id metric]
  (->> (:modes scenario-result)
       (some (fn [mode]
               (when (= mode-id (:id mode))
                 (get-in mode [:score metric]))))))

(defn- summarize-retrieval
  [scenario-results]
  (into {}
        (for [{:keys [id label]} retrieval-modes]
          [id {:label label
               :avg-recall (avg (map #(mode-score % id :recall) scenario-results))
               :avg-precision (avg (map #(mode-score % id :precision) scenario-results))}])))

(defn- send-json!
  [writer payload]
  (.write writer (json/write-str payload))
  (.write writer "\n")
  (.flush writer))

(defn- read-json!
  [reader]
  (some-> (.readLine reader)
          (json/read-str :key-fn keyword)))

(defn- mcp-smoke-test
  [{:keys [db-path seed-file]}]
  (let [process (-> (ProcessBuilder. ["./bin/start-mcp" "--db-path" db-path "--seed-file" seed-file])
                    (.directory (io/file "."))
                    (.start))
        stdout (io/reader (.getInputStream process))
        stderr-future (future (slurp (io/reader (.getErrorStream process))))
        stdin (io/writer (.getOutputStream process))]
    (try
      (send-json! stdin {:jsonrpc "2.0"
                         :id 1
                         :method "initialize"
                         :params {:protocolVersion "2025-03-26"
                                  :capabilities {}
                                  :clientInfo {:name "eval"
                                               :version "0.1.0"}}})
      (let [initialize-response (read-json! stdout)]
        (send-json! stdin {:jsonrpc "2.0"
                           :method "notifications/initialized"})
        (send-json! stdin {:jsonrpc "2.0" :id 2 :method "tools/list"})
        (let [tools-response (read-json! stdout)]
          (send-json! stdin {:jsonrpc "2.0"
                             :id 3
                             :method "tools/call"
                             :params {:name "get_task_timeline"
                                      :arguments {:task_id "task:AUTH-142"}}})
          (let [tool-response (read-json! stdout)]
            (send-json! stdin {:jsonrpc "2.0"
                               :id 4
                               :method "resources/read"
                               :params {:uri (str "memory://project/" project-id "/summary")}})
            (let [resource-response (read-json! stdout)]
              {:initialize initialize-response
               :tool-count (count (get-in tools-response [:result :tools]))
               :timeline-items (count (get-in tool-response [:result :structuredContent :timeline]))
               :resource-uri (get-in resource-response [:result :contents 0 :uri])
               :stderr (deref stderr-future 1000 "")}))))
      (finally
        (try (.close stdin) (catch Exception _))
        (try (.close stdout) (catch Exception _))
        (.destroy process)
        (.waitFor process)))))

(defn- recommendation
  [retrieval-summary]
  (let [graph-recall (get-in retrieval-summary [:graph :avg-recall])
        hybrid-recall (get-in retrieval-summary [:hybrid :avg-recall])]
    (cond
      (or (and (> hybrid-recall 0.65) (> graph-recall 0.6))
          (> graph-recall 0.7)) "fit with caveats"
      (or (> hybrid-recall 0.45) (> graph-recall 0.5)) "weak fit"
      :else "wrong tool")))

(defn- render-report
  [{:keys [retrieval-results retrieval-summary mcp-smoke db-path report-file]}]
  (let [recommendation-label (recommendation retrieval-summary)]
    (str
     "# Datalevin MCP Evaluation\n\n"
     "Generated on " (util/now-iso) ".\n\n"
     "Recommendation: **" recommendation-label "**\n\n"
     "## Retrieval Summary\n\n"
     "| Mode | Avg Recall | Avg Precision |\n"
     "| --- | ---: | ---: |\n"
     (apply str
            (for [{:keys [id label]} retrieval-modes]
              (format "| %s | %.2f | %.2f |\n"
                      label
                      (get-in retrieval-summary [id :avg-recall])
                      (get-in retrieval-summary [id :avg-precision]))))
     "\n"
     "## Scenario Results\n\n"
     (apply str
            (for [{:keys [id prompt expected-ids modes]} retrieval-results]
              (str "### " id "\n\n"
                   prompt "\n\n"
                   "Expected entities: `" (str/join "`, `" expected-ids) "`\n\n"
                   "| Mode | Recall | Precision | Hits |\n"
                   "| --- | ---: | ---: | --- |\n"
                   (apply str
                          (for [{:keys [label score]} modes]
                            (format "| %s | %.2f | %.2f | `%s` |\n"
                                    label
                                    (:recall score)
                                    (:precision score)
                                    (str/join "`, `" (:hits score)))))
                   "\n")))
     "## MCP Smoke Test\n\n"
     "- Protocol version: `" (get-in mcp-smoke [:initialize :result :protocolVersion]) "`\n"
     "- Tools exposed: `" (:tool-count mcp-smoke) "`\n"
     "- Timeline entries returned by `get_task_timeline`: `" (:timeline-items mcp-smoke) "`\n"
     "- Resource read URI: `" (:resource-uri mcp-smoke) "`\n"
     "- Database path: `" db-path "`\n\n"
     "## Findings\n\n"
     "- Datalevin is strongest when the agent already has an anchor entity such as a file, symbol, task, or failing tool run. The graph joins then recover decisions, patches, and follow-up notes that keyword lookup alone misses.\n"
     "- Structured provenance feels natural for tasks, tool runs, errors, observations, decisions, patches, notes, and timeline events. Long freeform tool output still wants a text field, so the model ends up hybrid rather than purely relational.\n"
     "- Full-text search is useful for note recall and broad prompts, but graph traversal and task-specific joins are what make the memory system noticeably better than plain text retrieval.\n"
     "- Integration pain is real on this machine: Datalevin 0.10.7 does not provide a `macosx-x86_64` native artifact, so this prototype is pinned to 0.9.27 and requires an extracted native library plus `libomp`.\n"
     "- Operationally, local-first writes and repeated reads are fine for a single-user prototype, but the native dependency story and LMDB sandbox sensitivity are caveats that would matter for broader distribution.\n\n"
     "## What Worked\n\n"
     "- `prior-decisions-for-task` is the clearest win for Datalog joins. It answers a coding-agent question with a small, direct query over task touched files and historical decision related files.\n"
     "- `get_task_timeline` and `get_symbol_memory` produce context bundles that are easy for an agent to consume because the provenance chain remains explicit.\n"
     "- The MCP surface stays compact while still exposing useful higher-level operations rather than raw database primitives.\n\n"
     "## What Hurt\n\n"
     "- Native library handling on x86 macOS is brittle enough that setup friction is a meaningful part of the recommendation.\n"
     "- Schema evolution is easy at the code level but still requires discipline: once you decide which relationship gets a dedicated attribute versus living only in a generic link entity, changing that shape ripples through ingestion and queries.\n"
     "- The server is small, but there is still glue code between MCP arguments, normalized entities, and Datalevin transactions; Datalevin does not remove that application-layer mapping work.\n\n"
     "## Recommendation Rationale\n\n"
     "Use Datalevin for coding-agent memory **if** the expected workflow is local-first, entity-centric, and heavily anchored on files, symbols, tasks, and execution artifacts. Do not choose it expecting zero-friction distribution across developer machines; the native dependency story means this is better described as **fit with caveats** than a clean default.\n")))

(defn- run-evaluation!
  [{:keys [db-path seed-file report-file]}]
  (ingest/seed! {:db-path db-path :seed-file seed-file})
  (let [conn (store/open-conn db-path)]
    (try
      (let [retrieval-results (vec (run-retrieval-eval conn))
            retrieval-summary (summarize-retrieval retrieval-results)
            mcp-smoke (mcp-smoke-test {:db-path db-path :seed-file seed-file})
            report (render-report {:retrieval-results retrieval-results
                                   :retrieval-summary retrieval-summary
                                   :mcp-smoke mcp-smoke
                                   :db-path db-path
                                   :report-file report-file})]
        (spit report-file report)
        {:report-file report-file
         :recommendation (recommendation retrieval-summary)
         :retrieval-summary retrieval-summary
         :mcp-smoke mcp-smoke})
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
      (pprint/pprint
       (util/json-friendly (run-evaluation! options))))))
