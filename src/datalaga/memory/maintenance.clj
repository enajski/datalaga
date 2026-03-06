(ns datalaga.memory.maintenance
  (:require [clojure.string :as str]
            [datalaga.memory.schema :as schema]
            [datalaga.memory.store :as store]
            [datalaga.util :as util]))

(def supported-operations
  [:normalize_entity_types
   :backfill_error_resolution
   :link_run_supersession
   :scope_file_ids_by_project])

(defn- ref-id
  [value]
  (cond
    (nil? value) nil
    (string? value) value
    (map? value) (:entity/id value)
    :else nil))

(defn- created-at-ms
  [entity]
  (some-> (:entity/created-at entity) .getTime))

(defn- sort-asc
  [entities]
  (->> entities
       (sort-by (fn [entity]
                  [(or (created-at-ms entity) Long/MIN_VALUE)
                   (:entity/id entity)]))
       vec))

(defn- normalize-operations
  [operations]
  (let [ops (if (seq operations)
              (mapv util/->keyword operations)
              supported-operations)]
    (when-let [unknown (seq (remove (set supported-operations) ops))]
      (throw (ex-info "Unsupported normalization operation"
                      {:unknown_operations (mapv name unknown)
                       :supported_operations (mapv name supported-operations)})))
    ops))

(defn- migration-event-id
  [project-id migration-id]
  (str "event:migration:" project-id ":" migration-id))

(defn- link-id
  [migration-id error-id run-id]
  (let [sanitize (fn [value]
                   (-> value
                       (str/replace #"[^\p{Alnum}:/#._-]+" "-")))]
    (str "link:migration:" (sanitize migration-id) ":resolved:" (sanitize error-id) ":" (sanitize run-id))))

(defn- normalize-entity-type-changes
  [project-entities]
  (reduce (fn [acc entity]
            (let [entity-id (:entity/id entity)
                  entity-type (:entity/type entity)]
              (if (or (nil? entity-id)
                      (nil? entity-type)
                      (contains? schema/entity-types entity-type)
                      (namespace entity-type))
                acc
                (conj acc {:entity/id entity-id
                           :entity/type (keyword "custom" (name entity-type))
                           :entity/kind (or (:entity/kind entity) entity-type)}))))
          []
          project-entities))

(defn- run-scope
  [run]
  [(:tool-run/command run)
   (ref-id (:entity/session run))
   (ref-id (:entity/task run))])

(defn- supersession-changes
  [project-entities]
  (let [runs (->> project-entities
                  (filter #(= :tool-run (:entity/type %))))
        groups (group-by run-scope runs)]
    (->> groups
         vals
         (mapcat (fn [same-scope-runs]
                   (let [ordered (sort-asc same-scope-runs)]
                     (->> (partition 2 1 ordered)
                          (map (fn [[prev-run next-run]]
                                 (let [prev-id (:entity/id prev-run)
                                       next-id (:entity/id next-run)
                                       known-supersedes (set (map ref-id (:tool-run/supersedes next-run)))
                                       known-retries (set (map ref-id (:tool-run/retries-of next-run)))]
                                   (when (and prev-id
                                              next-id
                                              (not (contains? known-supersedes prev-id))
                                              (not (contains? known-retries prev-id)))
                                     {:entity/id next-id
                                      :tool-run/supersedes [prev-id]
                                      :tool-run/retries-of [prev-id]}))))
                          (remove nil?)))))
         vec)))

(defn- resolution-run-candidate
  [error failing-run all-runs]
  (let [error-time (or (created-at-ms error) Long/MIN_VALUE)
        fail-time (or (created-at-ms failing-run) error-time)
        command (:tool-run/command failing-run)
        scoped-by-session (some-> (:entity/session error) ref-id)
        scoped-by-task (some-> (:entity/task error) ref-id)
        success-runs (->> all-runs
                          (filter #(= command (:tool-run/command %)))
                          (filter #(zero? (long (or (:tool-run/exit-code %) 0))))
                          (filter #(>= (or (created-at-ms %) Long/MIN_VALUE) fail-time))
                          sort-asc)
        session-matches (when scoped-by-session
                          (filter #(= scoped-by-session (ref-id (:entity/session %))) success-runs))
        task-matches (when scoped-by-task
                       (filter #(= scoped-by-task (ref-id (:entity/task %))) success-runs))]
    (or (first session-matches)
        (first task-matches)
        (first success-runs))))

(defn- bare-file-id?
  [entity-id]
  (and (string? entity-id)
       (str/starts-with? entity-id "file:")
       (not (str/starts-with? entity-id "file:project:"))))

(defn- file-id->path
  [file-id]
  (cond
    (not (string? file-id)) nil
    (str/starts-with? file-id "file:project:")
    (or (some->> file-id
                 (re-matches #"^file:project:[^:]+:(.+)$")
                 second)
        (subs file-id (count "file:")))
    (str/starts-with? file-id "file:")
    (subs file-id (count "file:"))
    :else nil))

(defn- project-scoped-file-id
  [project-id file-path]
  (str "file:" project-id ":" file-path))

(defn- collect-file-refs
  [entity]
  (reduce (fn [acc attr]
            (let [value (get entity attr)]
              (cond
                (nil? value) acc
                (schema/many-attrs attr)
                (into acc (keep ref-id (util/ensure-vector value)))
                :else
                (if-let [rid (ref-id value)]
                  (conj acc rid)
                  acc))))
          #{}
          schema/ref-attrs))

(defn- scoped-file-id-mapping
  [project-id project-entities]
  (let [from-file-entities (->> project-entities
                                (filter #(and (= :file (:entity/type %))
                                              (bare-file-id? (:entity/id %))))
                                (map :entity/id))
        from-refs (->> project-entities
                       (mapcat collect-file-refs)
                       (filter bare-file-id?))]
    (->> (concat from-file-entities from-refs)
         distinct
         (map (fn [old-id]
                [old-id (project-scoped-file-id project-id (file-id->path old-id))]))
         (into {}))))

(defn- scoped-file-entities
  [db-value project-id file-id-map]
  (->> file-id-map
       (map (fn [[old-id new-id]]
              (when-not (store/lookup-eid db-value new-id)
                (let [old-file (store/pull-entity-by-id db-value old-id)
                      path (file-id->path old-id)
                      timestamp (util/now-iso)]
                  {:entity/id new-id
                   :entity/type :file
                   :entity/name (or (:entity/name old-file) (.getName (java.io.File. path)))
                   :entity/path (or (:entity/path old-file) path)
                   :entity/language (:entity/language old-file)
                   :entity/summary (:entity/summary old-file)
                   :entity/body (or (:entity/body old-file) path)
                   :entity/project project-id
                   :entity/created-at (or (:entity/created-at old-file) timestamp)
                   :entity/updated-at timestamp
                   :entity/source "normalize_project_memory"
                   :file/module (:file/module old-file)}))))
       (remove nil?)
       vec))

(defn- scoped-file-ref-updates
  [project-entities file-id-map]
  (->> project-entities
       (mapcat (fn [entity]
                 (let [entity-id (:entity/id entity)]
                   (reduce (fn [acc attr]
                             (let [value (get entity attr)]
                               (cond
                                 (nil? value) acc

                                 (schema/many-attrs attr)
                                 (let [original (->> (util/ensure-vector value)
                                                     (map ref-id)
                                                     (remove nil?)
                                                     vec)
                                       mapped (->> original
                                                   (map #(get file-id-map % %))
                                                   (remove nil?)
                                                   distinct
                                                   vec)]
                                   (if (and (seq mapped) (not= (set mapped) (set original)))
                                     (conj acc {:entity/id entity-id
                                                attr mapped})
                                     acc))

                                 :else
                                 (let [rid (ref-id value)
                                       mapped (get file-id-map rid rid)]
                                   (if (and rid (not= mapped rid))
                                     (conj acc {:entity/id entity-id
                                                attr mapped})
                                     acc)))))
                           []
                           schema/ref-attrs))))
       vec))

(defn- scope-file-ids-by-project-changes
  [db-value project-id project-entities]
  (let [file-id-map (scoped-file-id-mapping project-id project-entities)
        file-entities (scoped-file-entities db-value project-id file-id-map)
        ref-updates (scoped-file-ref-updates project-entities file-id-map)]
    {:entities (vec (concat file-entities ref-updates))
     :warnings []}))

(defn- backfill-error-resolution-changes
  [db-value project-entities migration-id]
  (let [runs (->> project-entities (filter #(= :tool-run (:entity/type %))))
        runs-by-id (into {} (map (juxt :entity/id identity)) runs)
        links (->> project-entities
                   (filter #(and (= :link (:entity/type %))
                                 (= :resolved_by (:link/type %)))))
        existing-resolved (reduce (fn [acc link]
                                    (let [from-id (ref-id (:link/from link))
                                          to-id (ref-id (:link/to link))]
                                      (if (and from-id to-id)
                                        (update acc from-id (fnil conj #{}) to-id)
                                        acc)))
                                  {}
                                  links)]
    (reduce (fn [acc error]
              (let [error-id (:entity/id error)
                    status (:entity/status error)
                    has-resolved-link? (seq (get existing-resolved error-id))
                    open? (not (contains? #{:resolved :closed} status))
                    failing-run-id (ref-id (:error/tool-run error))
                    failing-run (get runs-by-id failing-run-id)]
                (cond
                  (or (nil? error-id) (not open?))
                  acc

                  (and has-resolved-link? open?)
                  (update acc :entities conj {:entity/id error-id
                                              :entity/status :resolved
                                              :entity/updated-at (util/now-iso)})

                  (or (nil? failing-run)
                      (str/blank? (:tool-run/command failing-run)))
                  (update acc :warnings conj {:error_id error-id
                                              :reason "missing_failing_tool_run"})

                  :else
                  (if-let [resolver (resolution-run-candidate error failing-run runs)]
                    (let [resolver-id (:entity/id resolver)
                          derived-link-id (link-id migration-id error-id resolver-id)
                          link-exists? (some? (store/lookup-eid db-value derived-link-id))]
                      (if link-exists?
                        (update acc :warnings conj {:error_id error-id
                                                    :reason "resolution_link_id_already_exists"
                                                    :link_id derived-link-id})
                        (-> acc
                            (update :entities conj {:entity/id derived-link-id
                                                    :entity/type :link
                                                    :entity/name "entity link"
                                                    :entity/summary "Backfilled resolved_by link from maintenance migration."
                                                    :entity/body "Backfilled resolved_by link from maintenance migration."
                                                    :entity/project (ref-id (:entity/project error))
                                                    :entity/session (ref-id (:entity/session error))
                                                    :entity/task (ref-id (:entity/task error))
                                                    :entity/created-at (util/now-iso)
                                                    :entity/updated-at (util/now-iso)
                                                    :entity/source "normalize_project_memory"
                                                    :link/from error-id
                                                    :link/to resolver-id
                                                    :link/type :resolved_by
                                                    :link/explanation "Auto-resolved from later successful run of same command."})
                            (update :entities conj {:entity/id error-id
                                                    :entity/status :resolved
                                                    :entity/updated-at (util/now-iso)
                                                    :entity/refs [resolver-id]}))))
                    (update acc :warnings conj {:error_id error-id
                                                :reason "no_resolution_candidate"})))))
            {:entities []
             :warnings []}
            (filter #(= :error (:entity/type %)) project-entities))))

(defn- proposed-changes
  [db-value project-id project-entities migration-id operations]
  (let [op-set (set operations)
        type-entities (if (contains? op-set :normalize_entity_types)
                        (normalize-entity-type-changes project-entities)
                        [])
        supersession-entities (if (contains? op-set :link_run_supersession)
                                (supersession-changes project-entities)
                                [])
        resolution-result (if (contains? op-set :backfill_error_resolution)
                            (backfill-error-resolution-changes db-value project-entities migration-id)
                            {:entities []
                             :warnings []})
        scoped-file-result (if (contains? op-set :scope_file_ids_by_project)
                             (scope-file-ids-by-project-changes db-value project-id project-entities)
                             {:entities []
                              :warnings []})
        by-op {:normalize_entity_types type-entities
               :link_run_supersession supersession-entities
               :backfill_error_resolution (:entities resolution-result)
               :scope_file_ids_by_project (:entities scoped-file-result)}
        all-entities (vec (mapcat by-op operations))]
    {:by-op by-op
     :all all-entities
     :warnings (vec (concat (:warnings resolution-result)
                            (:warnings scoped-file-result)))}))

(defn- op-counts
  [by-op operations]
  (into {}
        (map (fn [op]
               [(name op) (count (get by-op op []))]))
        operations))

(defn normalize-project-memory!
  [conn {:keys [project_id mode operations max_changes migration_id provenance]
         :or {mode "dry_run"
              max_changes 500}}]
  (let [project-id project_id
        normalized-mode (util/->keyword mode)
        normalized-ops (normalize-operations operations)
        migration-id (or migration_id (str "migration-v1-" (util/uuid-str)))
        db-value (store/db conn)
        _ (when-not (contains? #{:dry_run :apply} normalized-mode)
            (throw (ex-info "Unsupported normalization mode"
                            {:mode (name normalized-mode)
                             :supported_modes ["dry_run" "apply"]})))
        _ (when (str/blank? project-id)
            (throw (ex-info "project_id is required" {})))
        _ (when-not (store/lookup-eid db-value project-id)
            (throw (ex-info "Project does not exist" {:project-id project-id})))
        event-id (migration-event-id project-id migration-id)]
    (if (and (= :apply normalized-mode)
             (store/lookup-eid db-value event-id))
      {:project_id project-id
       :mode (name normalized-mode)
       :migration_id migration-id
       :operations (mapv name normalized-ops)
       :already_applied true
       :proposed_changes (assoc (into {} (map (fn [op] [(name op) 0]) normalized-ops)) "total" 0)
       :applied_changes (assoc (into {} (map (fn [op] [(name op) 0]) normalized-ops)) "total" 0)
       :touched_entity_ids []
       :warnings [{:reason "migration_id_already_applied"
                   :event_id event-id}]
       :event_id event-id}
      (let [fresh-db (store/db conn)
            project-entities (store/project-entities fresh-db project-id)
            {:keys [by-op all warnings]} (proposed-changes fresh-db project-id project-entities migration-id normalized-ops)
            counts (op-counts by-op normalized-ops)
            total-changes (count all)
            touch-sample (->> all
                              (map :entity/id)
                              (remove nil?)
                              distinct
                              (take 25)
                              vec)]
        (when (and (= :apply normalized-mode)
                   (> total-changes (long max_changes)))
          (throw (ex-info "Proposed changes exceed max_changes safety cap"
                          {:project-id project-id
                           :max_changes max_changes
                           :proposed_changes total-changes})))
        (if (= :dry_run normalized-mode)
        {:project_id project-id
         :mode (name normalized-mode)
         :migration_id migration-id
         :operations (mapv name normalized-ops)
         :proposed_changes (assoc counts "total" total-changes)
         :touched_entity_ids touch-sample
         :warnings warnings}
          (let [timestamp (util/now-iso)
                source (or (:source provenance) "normalize_project_memory")
                source-ref (:source_ref provenance)
                event {:entity/id event-id
                       :entity/type :event
                       :entity/name "project memory normalization"
                       :entity/summary (str "Applied maintenance normalization to " project-id ".")
                       :entity/body (str "operations=" (str/join "," (map name normalized-ops))
                                         "; proposed_changes=" total-changes
                                         "; max_changes=" max_changes)
                       :entity/project project-id
                       :entity/source source
                       :entity/source-ref source-ref
                       :entity/created-at timestamp
                       :entity/updated-at timestamp
                       :event/kind :maintenance
                       :event/at timestamp}
                tx-entities (conj all event)]
            (when (seq tx-entities)
              (store/transact-entities! conn tx-entities))
            {:project_id project-id
             :mode (name normalized-mode)
             :migration_id migration-id
             :operations (mapv name normalized-ops)
             :proposed_changes (assoc counts "total" total-changes)
             :applied_changes (assoc counts "total" total-changes)
             :touched_entity_ids touch-sample
             :warnings warnings
             :event_id event-id}))))))
