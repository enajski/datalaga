(ns datalaga.memory.store
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [datalaga.memory.schema :as schema]
            [datalaga.util :as util]))

(def default-db-path ".data/memory")

(def datalevin-opts
  {:validate-data? true
   :closed-schema? false})

(defn open-conn
  ([] (open-conn default-db-path))
  ([db-path]
   (d/get-conn db-path schema/schema datalevin-opts)))

(defn close!
  [conn]
  (d/close conn))

(defn db
  [conn]
  (d/db conn))

(defn lookup-eid
  [db-value entity-id]
  (d/q '[:find ?e .
         :in $ ?entity-id
         :where
         [?e :entity/id ?entity-id]]
       db-value
       entity-id))

(defn entity-count
  [db-value]
  (or (d/q '[:find (count ?e) .
             :where
             [?e :entity/id _]]
           db-value)
      0))

(defn database-empty?
  [conn]
  (zero? (entity-count (db conn))))

(defn project-id-for-entity
  [db-value entity-id]
  (when-let [entity (some->> entity-id
                             (lookup-eid db-value)
                             (d/pull db-value [:entity/id :entity/type {:entity/project [:entity/id]}]))]
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
  [db-value attr ref-id]
  (cond
    (nil? ref-id) nil
    (vector? ref-id) ref-id
    (map? ref-id) (resolve-ref-id db-value attr (:entity/id ref-id))
    (string? ref-id)
    (if (lookup-eid db-value ref-id)
      (entity-id->lookup-ref ref-id)
      (throw (ex-info "Referenced entity does not exist"
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
  [db-value new-ids entity]
  (doseq [[attr value] entity
          :when (and (schema/ref-attrs attr) (some? value))
          ref-id (ref-id-candidates value)]
    (when-not (or (contains? new-ids ref-id)
                  (lookup-eid db-value ref-id))
      (throw (ex-info "Referenced entity does not exist"
                      {:attr attr
                       :ref-id ref-id})))))

(defn- resolve-ref-value
  [db-value attr value]
  (if (schema/many-attrs attr)
    (->> value
         util/ensure-vector
         (mapv #(resolve-ref-id db-value attr %)))
    (resolve-ref-id db-value attr value)))

(defn- build-scalar-tx
  [entity]
  (let [scalar-map (->> entity
                        (remove (fn [[attr value]]
                                  (or (nil? value)
                                      (schema/ref-attrs attr)
                                      (and (= attr :entity/id) (str/blank? value)))))
                        (into {}))]
    (when (> (count scalar-map) 1)
      scalar-map)))

(defn- build-ref-tx
  [db-value entity]
  (let [ref-map (reduce-kv (fn [acc attr value]
                             (if (or (nil? value) (not (schema/ref-attrs attr)))
                               acc
                               (assoc acc attr (resolve-ref-value db-value attr value))))
                           {}
                           entity)]
    (when (seq ref-map)
      (assoc ref-map :db/id (entity-id->lookup-ref (:entity/id entity))))))

(defn- known-new-ids
  [entities]
  (->> entities
       (filter :entity/type)
       (map :entity/id)
       set))

(defn- validate-entity!
  [db-value new-ids entity]
  (when-not (:entity/id entity)
    (throw (ex-info "Entity is missing :entity/id" {:entity entity})))
  (when (and (nil? (:entity/type entity))
             (nil? (lookup-eid db-value (:entity/id entity)))
             (not (contains? new-ids (:entity/id entity))))
    (throw (ex-info "New entity requires :entity/type"
                    {:entity-id (:entity/id entity)
                     :entity entity}))))

(defn transact-entities!
  [conn entities]
  (let [merged-entities (coalesce-entities entities)
        starting-db (db conn)
        new-ids (known-new-ids merged-entities)]
    (doseq [entity merged-entities]
      (validate-entity! starting-db new-ids entity)
      (validate-ref-attrs! starting-db new-ids entity))
    (when-let [scalar-tx (seq (keep build-scalar-tx merged-entities))]
      (d/transact! conn scalar-tx))
    (let [db-after-scalars (db conn)]
      (when-let [ref-tx (seq (keep #(build-ref-tx db-after-scalars %) merged-entities))]
        (d/transact! conn ref-tx)))
    {:entity-ids (mapv :entity/id merged-entities)
     :entity-count (count merged-entities)}))

(defn pull-entity-by-id
  ([db-value entity-id]
   (pull-entity-by-id db-value entity-id schema/readable-pull))
  ([db-value entity-id pull-pattern]
   (when-let [eid (lookup-eid db-value entity-id)]
     (d/pull db-value pull-pattern eid))))

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
          (d/q '[:find [?entity-id ...]
                 :in $ ?project
                 :where
                 [?e :entity/project ?project]
                 [?e :entity/id ?entity-id]]
               db-value
               project-eid))
         distinct
         vec)))

(defn project-entities
  [db-value project-id]
  (pull-entities-by-id db-value (project-entity-ids db-value project-id)))

(defn entities-by-type
  ([db-value entity-type]
   (entities-by-type db-value entity-type nil))
  ([db-value entity-type project-id]
   (let [project-eid (some->> project-id (lookup-eid db-value))]
     (->> (if project-eid
            (d/q '[:find [?entity-id ...]
                   :in $ ?entity-type ?project
                   :where
                   [?e :entity/type ?entity-type]
                   [?e :entity/project ?project]
                   [?e :entity/id ?entity-id]]
                 db-value
                 entity-type
                 project-eid)
            (d/q '[:find [?entity-id ...]
                   :in $ ?entity-type
                   :where
                   [?e :entity/type ?entity-type]
                   [?e :entity/id ?entity-id]]
                 db-value
                 entity-type))
          (pull-entities-by-id db-value)
          vec))))

(defn search-body
  [db-value query {:keys [limit predicate]
                   :or {limit 10
                        predicate (constantly true)}}]
  (letfn [(entry->eid [entry]
            (cond
              (nil? entry) nil
              (vector? entry) (first entry)
              :else (d/datom-e entry)))]
    (->> (d/fulltext-datoms db-value query)
         (map entry->eid)
         (remove nil?)
         distinct
         (map #(d/pull db-value schema/readable-pull %))
         (remove nil?)
         (filter predicate)
         (take limit)
         vec)))

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
    :entity/path (:entity/path entity)
    :entity/status (:entity/status entity)
    :entity/created-at (:entity/created-at entity)}))

(defn all-project-ids
  [db-value]
  (or (d/q '[:find [?entity-id ...]
             :where
             [?e :entity/type :project]
             [?e :entity/id ?entity-id]]
           db-value)
      []))
