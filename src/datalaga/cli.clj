(ns datalaga.cli
  "Unified CLI for datalaga — invoke any MCP tool directly from the
  command line without the JSON-RPC protocol layer.

  Usage:  datalaga [global-opts] <tool> [--arg value ...]
          datalaga help [tool]
          datalaga tools"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [datalaga.ingest :as ingest]
            [datalaga.mcp.server :as mcp]
            [datalaga.memory.store :as store]
            [datalaga.util :as util])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Global CLI options (parsed before tool name)
;; ---------------------------------------------------------------------------

(def global-options
  [["-d" "--db-path PATH" "Path to the Datalevin database directory."
    :default store/default-db-path]
   ["-s" "--seed-file FILE" "Path to the EDN seed dataset."
    :default ingest/default-seed-file]
   [nil "--seed-on-start" "Seed the database before running the command."
    :default false]
   ["-f" "--format FORMAT" "Output format: pretty | json | edn"
    :default "pretty"
    :validate [#(contains? #{"pretty" "json" "edn"} %)
               "Must be pretty, json, or edn"]]
   [nil "--json JSON" "Pass tool arguments as a JSON object string."]
   ["-h" "--help"]])

;; ---------------------------------------------------------------------------
;; Tool argument parsing
;; ---------------------------------------------------------------------------

(defn- try-parse-json-value
  "Attempt to parse a string as a JSON value.  Returns the parsed
  value for arrays, objects, and explicit null/boolean literals.
  Numbers and plain strings are returned as-is so that IDs like
  `project:foo` are never mangled."
  [s]
  (cond
    (nil? s) nil
    ;; JSON object or array — always parse
    (or (str/starts-with? s "{") (str/starts-with? s "["))
    (try (json/read-str s :key-fn keyword) (catch Exception _ s))
    ;; integer
    (re-matches #"-?\d+" s)
    (try (Long/parseLong s) (catch Exception _ s))
    ;; boolean / null literals
    (= "true" s) true
    (= "false" s) false
    (= "null" s) nil
    ;; everything else is a plain string
    :else s))

(defn- parse-tool-args
  "Parse a flat sequence of `--key value` pairs into a keyword map.
  Repeated keys are collected into vectors."
  [args]
  (loop [remaining args
         result {}]
    (if (empty? remaining)
      result
      (let [token (first remaining)]
        (if (str/starts-with? token "--")
          (let [key-name (keyword (subs token 2))
                [value rest-args] (if (or (empty? (rest remaining))
                                          (str/starts-with? (second remaining) "--"))
                                    [true (rest remaining)]
                                    [(try-parse-json-value (second remaining))
                                     (drop 2 remaining)])]
            (recur rest-args
                    (if (contains? result key-name)
                      ;; repeated key → accumulate into vector
                      (update result key-name
                              (fn [prev]
                                (if (vector? prev)
                                  (conj prev value)
                                  [prev value])))
                      (assoc result key-name value))))
          ;; skip non-flag tokens (shouldn't happen after tool name extraction)
          (recur (rest remaining) result))))))

(defn- merge-json-args
  "Merge --json blob into parsed tool args (tool args win on conflict)."
  [tool-args json-str]
  (if (str/blank? json-str)
    tool-args
    (let [parsed (json/read-str json-str :key-fn keyword)]
      (merge parsed tool-args))))

(defn- read-stdin-json
  "Read a single JSON object from stdin."
  []
  (let [input (slurp *in*)]
    (when-not (str/blank? input)
      (json/read-str input :key-fn keyword))))

;; ---------------------------------------------------------------------------
;; Output formatting
;; ---------------------------------------------------------------------------

(defn- format-output
  [value fmt]
  (case fmt
    "json" (json/write-str (util/json-friendly value) :escape-slash false)
    "edn" (pr-str value)
    "pretty" (with-out-str (pprint/pprint (util/json-friendly value)))))

;; ---------------------------------------------------------------------------
;; Tool name normalization
;; ---------------------------------------------------------------------------

(def ^:private known-tool-names
  "Set of canonical MCP tool names."
  (set (map :name (mcp/tools))))

(defn- normalize-tool-name
  "Accept hyphens or underscores and map to the canonical underscore form."
  [raw-name]
  (let [underscore-form (str/replace raw-name "-" "_")]
    (when (contains? known-tool-names underscore-form)
      underscore-form)))

;; ---------------------------------------------------------------------------
;; Help text
;; ---------------------------------------------------------------------------

(defn- tool-help
  "Print detailed help for a single tool."
  [tool-name]
  (let [tool-meta (first (filter #(= tool-name (:name %)) (mcp/tools)))]
    (if tool-meta
      (str/join
       \newline
       [(str "Tool: " (:name tool-meta))
        ""
        (:description tool-meta)
        ""
        "Required arguments:"
        (let [required (get-in tool-meta [:inputSchema :required])]
          (if (seq required)
            (str/join \newline (map #(str "  --" %) required))
            "  (none)"))
        ""
        "All arguments:"
        (let [props (get-in tool-meta [:inputSchema :properties])]
          (str/join \newline
                    (map (fn [[k v]]
                           (let [type-str (cond
                                           (string? (:type v)) (:type v)
                                           (vector? (:type v)) (str/join "|" (:type v))
                                           :else "string")]
                             (str "  --" (name k) " <" type-str ">")))
                         (sort-by key props))))
        ""])
      (str "Unknown tool: " tool-name "\nRun 'datalaga tools' to list available tools."))))

(defn- tools-list
  "Return formatted list of all available tools."
  []
  (str/join
   \newline
   (concat
    ["Available tools:" ""]
    (map (fn [tool]
           (str "  " (:name tool)
                (when-let [desc (:description tool)]
                  (str "\n    " desc))))
         (sort-by :name (mcp/tools)))
    ["" "Run 'datalaga help <tool>' for detailed argument info."])))

(defn- usage
  [summary]
  (str/join
   \newline
   ["datalaga — direct CLI for Datalevin coding memory"
    ""
    "Usage:"
    "  datalaga [options] <tool> [--arg value ...]"
    "  datalaga [options] <tool> --json '{\"key\":\"value\"}'"
    "  echo '{...}' | datalaga [options] <tool> -"
    "  datalaga tools"
    "  datalaga help <tool>"
    ""
    "Global options:"
    summary
    ""
    "Examples:"
    "  datalaga list_projects"
    "  datalaga ensure_project --project_id project:myapp --name \"My App\""
    "  datalaga search_entities --project_id project:myapp --query auth"
    "  datalaga record_tool_run --run_id run:001 --project_id project:myapp --command \"npm test\""
    "  datalaga summarize_project_memory --project_id project:myapp -f json"
    "  datalaga memory_query --query_edn '[:find [?id ...] :where [?e :entity/id ?id]]'"
    ""
    "Run 'datalaga tools' to list all available tools."]))

;; ---------------------------------------------------------------------------
;; Core execution
;; ---------------------------------------------------------------------------

(defn- eprintln
  [& values]
  (binding [*out* *err*]
    (apply println values)))

(defn- run-tool!
  "Open connection, optionally seed, call the tool, print result, close."
  [{:keys [db-path seed-file seed-on-start format json]} tool-name tool-args stdin?]
  (when seed-on-start
    (ingest/seed! {:db-path db-path :seed-file seed-file}))
  (let [stdin-args (when stdin? (read-stdin-json))
        merged-args (cond-> {}
                      stdin-args (merge stdin-args)
                      json (merge-json-args json)
                      true (merge tool-args))
        conn (store/open-conn db-path)]
    (try
      (let [result (mcp/call-tool! conn tool-name merged-args)]
        (println (format-output result format)))
      (catch clojure.lang.ExceptionInfo ex
        (let [data (ex-data ex)]
          (eprintln (str "Error: " (.getMessage ex)))
          (when (seq data)
            (eprintln (format-output data format)))
          (System/exit 1)))
      (catch Exception ex
        (eprintln (str "Error: " (.getMessage ex)))
        (System/exit 1))
      (finally
        (store/close! conn)))))

;; ---------------------------------------------------------------------------
;; Global-flag extraction
;; ---------------------------------------------------------------------------

(def ^:private global-flag-keys
  "Short and long flags for global options (including value-bearing ones)."
  #{"-d" "--db-path" "-s" "--seed-file" "--seed-on-start" "-f" "--format"
    "--json" "-h" "--help"})

(def ^:private global-value-flags
  "Global flags that consume the next token as their value."
  #{"-d" "--db-path" "-s" "--seed-file" "-f" "--format" "--json"})

(defn- extract-global-args
  "Walk the full arg list and pull out global flags (with values)
  from wherever they appear.  Returns [global-args rest-args]."
  [args]
  (loop [remaining (vec args)
         global-args []
         rest-args []]
    (if (empty? remaining)
      [global-args rest-args]
      (let [token (first remaining)]
        (cond
          (contains? global-value-flags token)
          (recur (subvec remaining (min 2 (count remaining)))
                 (into global-args (take 2 remaining))
                 rest-args)

          (contains? global-flag-keys token)
          (recur (subvec remaining 1)
                 (conj global-args token)
                 rest-args)

          :else
          (recur (subvec remaining 1)
                 global-args
                 (conj rest-args token)))))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main
  [& args]
  (let [[global-args rest-args] (extract-global-args args)
        {:keys [options summary errors]}
        (parse-opts global-args global-options)
        [command & remaining] rest-args]
    (cond
      (:help options)
      (println (usage summary))

      (seq errors)
      (do
        (doseq [error errors]
          (eprintln error))
        (System/exit 1))

      (nil? command)
      (println (usage summary))

      (= "tools" command)
      (println (tools-list))

      (= "help" command)
      (if-let [tool-name (some-> (first remaining) normalize-tool-name)]
        (println (tool-help tool-name))
        (if (first remaining)
          (do (eprintln (str "Unknown tool: " (first remaining)))
              (System/exit 1))
          (println (usage summary))))

      :else
      (let [canonical-name (normalize-tool-name command)]
        (if-not canonical-name
          (do (eprintln (str "Unknown tool: " command))
              (eprintln "Run 'datalaga tools' to list available tools.")
              (System/exit 1))
          (let [stdin? (= "-" (first remaining))
                tool-arg-tokens (if stdin? (rest remaining) remaining)
                tool-args (parse-tool-args tool-arg-tokens)]
            (run-tool! options canonical-name tool-args stdin?)))))))
