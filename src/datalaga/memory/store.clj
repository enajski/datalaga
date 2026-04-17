(ns datalaga.memory.store
  (:require [clojure.string :as str]
            [datalaga.memory.backend :as memory-backend]
            [datalaga.memory.schema :as schema]
            [datalaga.util :as util]))

(def default-backend memory-backend/default-backend)
(def default-db-path memory-backend/default-db-path)
(def supported-backends memory-backend/supported-backends)

(defn default-db-path-for
  [backend]
  (memory-backend/default-db-path-for backend))

(defn normalize-backend
  [backend]
  (memory-backend/normalize-backend backend))

(defn- normalize-open-config
  [config-or-path]
  (let [{:keys [backend db-path]} (if (map? config-or-path)
                                    config-or-path
                                    {:backend default-backend
                                     :db-path config-or-path})
        backend (normalize-backend backend)
        db-path (cond
                  (and (= backend :datascript-sqlite)
                       (or (nil? db-path)
                           (= db-path default-db-path)))
                  (default-db-path-for backend)

                  (nil? db-path)
                  (default-db-path-for backend)

                  :else db-path)]
    {:backend backend
     :db-path db-path}))

(defn- backend-open-fn
  [backend]
  (case (normalize-backend backend)
    :datalevin (requiring-resolve 'datalaga.memory.backends.datalevin/open-conn)
    :datascript-sqlite (requiring-resolve 'datalaga.memory.backends.datascript-sqlite/open-conn)))

(defn open-conn
  ([] (open-conn {:backend default-backend}))
  ([config-or-path]
   (let [{:keys [backend db-path]} (normalize-open-config config-or-path)]
     ((backend-open-fn backend) db-path))))

(defn close!
  [conn]
  (memory-backend/close-connection! conn))

(defn backend-key
  [conn]
  (memory-backend/backend-key conn))

(defn backend-capabilities
  [conn]
  (memory-backend/backend-capabilities conn))

(defn db
  [conn]
  (memory-backend/snapshot conn))

(defn query
  [db-value query-form & inputs]
  (memory-backend/query-snapshot db-value query-form inputs))

(defn pull
  [db-value pull-pattern entity-ref]
  (memory-backend/pull-snapshot db-value pull-pattern entity-ref))

(defn lookup-eid
  [db-value entity-id]
  (query db-value
         '[:find ?e .
           :in $ ?entity-id
           :where
           [?e :entity/id ?entity-id]]
         entity-id))

(defn entity-count
  [db-value]
  (or (query db-value
             '[:find (count ?e) .
               :where
               [?e :entity/id _]])
      0))

(defn database-empty?
  [conn]
  (zero? (entity-count (db conn))))

(defn project-id-for-entity
  [db-value entity-id]
  (when-let [entity (some->> entity-id
                             (lookup-eid db-value)
                             (pull db-value [:entity/id :entity/type {:entity/project [:entity/id]}]))]
    (or (get-in entity [:entity/project :entity/id])
        (when (= :project (:entity/type entity))
          (:entity/id entity)))))

(defn normalize-attr-value
  [attr value]
  (cond
    (nil? value) nil
    (schema/keyword-valued-attrs attr) (util/->keyword value)
    (schema/instant-attrs attr) (util/->date value)
    (and (schema/many-attrs attr) (sequential? value))
    (->> value (remove nil?) vec)
    :else value))

(defn prepare-entity
  [entity]
  (reduce-kv (fn [acc key value]
               (let [attr (if (string? key) (util/->keyword key) key)]
                 (assoc acc attr (normalize-attr-value attr value))))
             {}
             entity))

(defn- merge-attr
  [attr old-value new-value]
  (if (schema/many-attrs attr)
    (->> (concat (util/ensure-vector old-value)
                 (util/ensure-vector new-value))
         (remove nil?)
         distinct
         vec)
    (or new-value old-value)))

(defn coalesce-entities
  [entities]
  (->> entities
       (map prepare-entity)
       (reduce (fn [acc entity]
                 (let [entity-id (:entity/id entity)]
                   (when-not entity-id
                     (throw (ex-info "Entity is missing :entity/id" {:entity entity})))
                   (update acc entity-id
                           (fn [existing]
                             (reduce-kv (fn [merged attr value]
                                          (assoc merged attr (merge-attr attr (get merged attr) value)))
                                        (or existing {})
                                        entity)))))
               {})
       vals))

(defn- entity-id->lookup-ref
  [entity-id]
  [:entity/id entity-id])

(defn- resolve-ref-id
  [db-value new-tempids attr ref-id]
  (cond
    (nil? ref-id) nil
    (vector? ref-id) ref-id
    (map? ref-id) (resolve-ref-id db-value new-tempids attr (:entity/id ref-id))
    (string? ref-id)
    (cond
      (contains? new-tempids ref-id) (get new-tempids ref-id)
      (lookup-eid db-value ref-id) (entity-id->lookup-ref ref-id)
      :else (throw (ex-info "Referenced entity does not exist"
                            {:attr attr :ref-id ref-id})))
    :else (throw (ex-info "Unsupported ref value" {:attr attr :ref-id ref-id}))))

(defn- ref-id-candidates
  [value]
  (cond
    (nil? value) []
    (and (vector? value)
         (= 2 (count value))
         (= :entity/id (first value))
         (string? (second value)))
    [(second value)]
    (vector? value) (mapcat ref-id-candidates value)
    (sequential? value) (mapcat ref-id-candidates value)
    (map? value) (if-let [entity-id (:entity/id value)]
                   [entity-id]
                   [])
    (string? value) [value]
    :else (throw (ex-info "Unsupported ref value" {:value value}))))

(defn- validate-ref-attrs!
  [db-value new-tempids entity]
  (doseq [[attr value] entity
          :when (and (schema/ref-attrs attr) (some? value))
          ref-id (ref-id-candidates value)]
    (when-not (or (contains? new-tempids ref-id)
                  (lookup-eid db-value ref-id))
      (throw (ex-info "Referenced entity does not exist"
                      {:attr attr
                       :ref-id ref-id})))))

(defn- resolve-ref-value
  [db-value new-tempids attr value]
  (if (schema/many-attrs attr)
    (->> value
         util/ensure-vector
         (mapv #(resolve-ref-id db-value new-tempids attr %)))
    (resolve-ref-id db-value new-tempids attr value)))

(defn- build-entity-tx
  [db-value new-tempids entity]
  (let [entity-id (:entity/id entity)
        db-id (or (get new-tempids entity-id)
                  (entity-id->lookup-ref entity-id))
        tx-map (reduce-kv (fn [acc attr value]
                            (cond
                              (nil? value) acc
                              (and (= attr :entity/id) (str/blank? value)) acc
                              (schema/ref-attrs attr) (assoc acc attr (resolve-ref-value db-value new-tempids attr value))
                              :else (assoc acc attr value)))
                          {:db/id db-id}
                          entity)]
    (when (> (count (dissoc tx-map :db/id)) 1)
      tx-map)))

(defn- new-id->tempid
  [db-value entities]
  (let [new-ids (->> entities
                     (map :entity/id)
                     (filter #(nil? (lookup-eid db-value %)))
                     distinct
                     vec)]
    (into {}
          (map-indexed (fn [idx entity-id]
                         [entity-id (- (inc idx))])
                       new-ids))))

(defn- validate-entity!
  [db-value new-tempids entity]
  (when-not (:entity/id entity)
    (throw (ex-info "Entity is missing :entity/id" {:entity entity})))
  (when (and (nil? (:entity/type entity))
             (nil? (lookup-eid db-value (:entity/id entity)))
             (not (contains? new-tempids (:entity/id entity))))
    (throw (ex-info "New entity requires :entity/type"
                    {:entity-id (:entity/id entity)
                     :entity entity}))))

(defn transact-entities!
  [conn entities]
  (let [merged-entities (coalesce-entities entities)
        starting-db (db conn)
        new-tempids (new-id->tempid starting-db merged-entities)]
    (doseq [entity merged-entities]
      (validate-entity! starting-db new-tempids entity)
      (validate-ref-attrs! starting-db new-tempids entity))
    (when-let [tx-data (seq (keep #(build-entity-tx starting-db new-tempids %) merged-entities))]
      (memory-backend/transact-tx! conn tx-data))
    {:entity-ids (mapv :entity/id merged-entities)
     :entity-count (count merged-entities)}))

(defn pull-entity-by-id
  ([db-value entity-id]
   (pull-entity-by-id db-value entity-id schema/readable-pull))
  ([db-value entity-id pull-pattern]
   (when-let [eid (lookup-eid db-value entity-id)]
     (pull db-value pull-pattern eid))))

(defn pull-entities-by-id
  ([db-value entity-ids]
   (pull-entities-by-id db-value entity-ids schema/readable-pull))
  ([db-value entity-ids pull-pattern]
   (->> entity-ids
        distinct
        (map #(pull-entity-by-id db-value % pull-pattern))
        (remove nil?)
        vec)))

(defn project-entity-ids
  [db-value project-id]
  (let [project-eid (lookup-eid db-value project-id)]
    (when-not project-eid
      (throw (ex-info "Project does not exist" {:project-id project-id})))
    (->> (concat
          [project-id]
          (query db-value
                 '[:find [?entity-id ...]
                   :in $ ?project
                   :where
                   [?e :entity/project ?project]
                   [?e :entity/id ?entity-id]]
                 project-eid))
         distinct
         vec)))

(defn project-eid
  [db-value project-id]
  (lookup-eid db-value project-id))

(defn require-project-eid
  [db-value project-id]
  (or (project-eid db-value project-id)
      (throw (ex-info "Project does not exist" {:project-id project-id}))))

(defn project-entity-ids-by-type
  [db-value project-eid entity-type]
  (query db-value
         '[:find [?entity-id ...]
           :in $ ?project ?entity-type
           :where
           [?e :entity/project ?project]
           [?e :entity/type ?entity-type]
           [?e :entity/id ?entity-id]]
         project-eid
         entity-type))

(defn project-entity-ids-for-eid
  [db-value project-eid]
  (query db-value
         '[:find [?entity-id ...]
           :in $ ?project
           :where
           [?e :entity/project ?project]
           [?e :entity/id ?entity-id]]
         project-eid))

(defn project-entity-ids-by-type-and-attr-value
  [db-value project-eid entity-type attr value]
  (query db-value
         '[:find [?entity-id ...]
           :in $ ?project ?entity-type ?attr ?value
           :where
           [?e :entity/project ?project]
           [?e :entity/type ?entity-type]
           [?e ?attr ?value]
           [?e :entity/id ?entity-id]]
         project-eid
         entity-type
         attr
         value))

(defn project-max-instant
  [db-value project-eid attr]
  (query db-value
         '[:find (max ?ts) .
           :in $ ?project ?attr
           :where
           [?e :entity/project ?project]
           [?e ?attr ?ts]]
         project-eid
         attr))

(defn project-ref-edge-pairs
  [db-value project-eid attr]
  (query db-value
         '[:find ?src-id ?dst-id
           :in $ ?project ?attr
           :where
           [?e :entity/project ?project]
           [?e :entity/id ?src-id]
           [?e ?attr ?dst]
           [?dst :entity/id ?dst-id]]
         project-eid
         attr))

(defn project-entities
  [db-value project-id]
  (pull-entities-by-id db-value (project-entity-ids db-value project-id)))

(defn entities-by-type
  ([db-value entity-type]
   (entities-by-type db-value entity-type nil))
  ([db-value entity-type project-id]
   (let [project-eid (some->> project-id (lookup-eid db-value))]
     (->> (if project-eid
            (query db-value
                   '[:find [?entity-id ...]
                     :in $ ?entity-type ?project
                     :where
                     [?e :entity/type ?entity-type]
                     [?e :entity/project ?project]
                     [?e :entity/id ?entity-id]]
                   entity-type
                   project-eid)
            (query db-value
                   '[:find [?entity-id ...]
                     :in $ ?entity-type
                     :where
                     [?e :entity/type ?entity-type]
                     [?e :entity/id ?entity-id]]
                   entity-type))
          (pull-entities-by-id db-value)
          vec))))

(defn search-body
  [db-value query {:keys [limit predicate]
                   :or {limit 10
                        predicate (constantly true)}}]
  (memory-backend/search-body-snapshot db-value query {:limit limit
                                                       :predicate predicate}))

(defn distinct-summaries
  [entities]
  (->> entities
       (util/distinct-by :entity/id)
       (sort-by (juxt :entity/created-at :entity/id))
       reverse
       vec))

(defn entity-brief
  [entity]
  (util/compact-map
  {:entity/id (:entity/id entity)
   :entity/type (:entity/type entity)
   :entity/name (:entity/name entity)
   :entity/summary (:entity/summary entity)
   :entity/external-refs (:entity/external-refs entity)
   :entity/path (:entity/path entity)
   :entity/status (:entity/status entity)
   :entity/created-at (:entity/created-at entity)}))

(defn all-project-ids
  [db-value]
  (or (query db-value
             '[:find [?entity-id ...]
               :where
               [?e :entity/type :project]
               [?e :entity/id ?entity-id]])
      []))
