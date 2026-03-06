(ns datalaga.memory.queries
  (:require [clojure.set :as set]
            [datalaga.memory.schema :as schema]
            [datalaga.memory.store :as store]
            [datalaga.util :as util]))

(defn- created-at-ms
  [entity]
  (some-> (:entity/created-at entity) .getTime))

(defn- sort-desc
  [entities]
  (->> entities
       (sort-by (fn [entity]
                  [(or (created-at-ms entity) Long/MIN_VALUE)
                   (:entity/id entity)]))
       reverse
       vec))

(defn- sort-asc
  [entities]
  (->> entities
       (sort-by (fn [entity]
                  [(or (created-at-ms entity) Long/MIN_VALUE)
                   (:entity/id entity)]))
       vec))

(defn- direct-ref-ids
  [entity]
  (->> entity
       vals
       (mapcat (fn collect [value]
                 (cond
                   (and (map? value) (:entity/id value)) [(:entity/id value)]
                   (map? value) (mapcat collect (vals value))
                   (sequential? value) (mapcat collect value)
                   :else [])))
       set))

(defn- entity-project-id
  [entity]
  (or (get-in entity [:entity/project :entity/id])
      (when (= :project (:entity/type entity))
        (:entity/id entity))))

(defn- brief
  [entity]
  (store/entity-brief entity))

(defn- filter-project
  [project-id entities]
  (filter #(= project-id (entity-project-id %)) entities))

(defn- group-briefs
  [entities]
  (into {}
        (map (fn [[entity-type matches]]
               [entity-type (mapv brief matches)]))
        (group-by :entity/type entities)))

(defn- unresolved-error?
  [entity]
  (and (= :error (:entity/type entity))
       (not (contains? #{:resolved :closed} (:entity/status entity)))))

(def closed-task-statuses
  #{:done :completed :closed :cancelled})

(defn- active-task?
  [entity]
  (and (= :task (:entity/type entity))
       (not (contains? closed-task-statuses (:entity/status entity)))))

(defn exact-lookup
  [conn {:keys [project-id term limit]
         :or {limit 10}}]
  (let [db-value (store/db conn)
        project-filter (fn [entity]
                         (or (nil? project-id)
                             (= project-id (entity-project-id entity))))
        target (or term "")
        target-lower (.toLowerCase target)]
    (->> (if project-id
           (store/project-entities db-value project-id)
           (mapcat #(store/project-entities db-value %)
                   (store/all-project-ids db-value)))
         (filter project-filter)
         (filter (fn [entity]
                   (some #(= target-lower (.toLowerCase ^String %))
                         (remove nil?
                                 [(:entity/id entity)
                                  (:entity/name entity)
                                  (:entity/path entity)
                                  (get entity :symbol/qualified-name)]))))
         sort-desc
         (take limit)
         vec)))

(defn project-summary
  [conn project-id]
  (let [db-value (store/db conn)
        entities (store/project-entities db-value project-id)
        counts (->> entities
                    (group-by :entity/type)
                    (into {}
                          (map (fn [[entity-type matches]]
                                 [entity-type (count matches)]))))
        recent-patches (->> entities (filter #(= :patch (:entity/type %))) sort-desc (take 5) (mapv brief))
        recent-failures (->> entities (filter unresolved-error?) sort-desc (take 5) (mapv brief))
        active-tasks (->> entities
                          (filter active-task?)
                          sort-desc
                          (mapv brief))]
    {:project (brief (store/pull-entity-by-id db-value project-id))
     :counts counts
     :recent-patches recent-patches
     :recent-failures recent-failures
     :active-tasks active-tasks}))

(defn summarize-project-memory
  [conn {:keys [project-id]}]
  (project-summary conn project-id))

(defn recent-failures
  [conn project-id]
  (let [db-value (store/db conn)]
    (->> (store/project-entities db-value project-id)
         (filter unresolved-error?)
         sort-desc
         (mapv brief))))

(defn active-tasks
  [conn project-id]
  (let [db-value (store/db conn)]
    (->> (store/project-entities db-value project-id)
         (filter active-task?)
         sort-desc
         (mapv brief))))

(defn session-timeline
  [conn session-id]
  (let [db-value (store/db conn)
        session (store/pull-entity-by-id db-value session-id)
        project-id (entity-project-id session)]
    {:session (brief session)
     :entries (->> (store/project-entities db-value project-id)
                   (filter #(= session-id (get-in % [:entity/session :entity/id])))
                   (filter #(contains? schema/timeline-types (:entity/type %)))
                   sort-asc
                   (mapv brief))}))

(defn search-notes
  [conn {:keys [project-id query limit]
         :or {limit 5}}]
  (let [db-value (store/db conn)]
    {:project-id project-id
     :query query
     :matches (->> (store/search-body db-value query {:limit (* 3 limit)
                                                      :predicate #(and (= :note (:entity/type %))
                                                                       (= project-id (entity-project-id %)))})
                   (take limit)
                   (mapv brief))}))

(defn- build-graph
  [entities]
  (reduce (fn [acc entity]
            (let [entity-id (:entity/id entity)
                  neighbors (disj (direct-ref-ids entity) entity-id)]
              (reduce (fn [graph neighbor-id]
                        (-> graph
                            (update entity-id (fnil conj #{}) neighbor-id)
                            (update neighbor-id (fnil conj #{}) entity-id)))
                      (update acc entity-id (fnil into #{}) neighbors)
                      neighbors)))
          {}
          entities))

(defn find-related-context
  [conn {:keys [entity-id limit hops]
         :or {limit 8
              hops 2}}]
  (let [db-value (store/db conn)
        start (store/pull-entity-by-id db-value entity-id)
        project-id (entity-project-id start)
        entities (store/project-entities db-value project-id)
        by-id (into {} (map (juxt :entity/id identity)) entities)
        graph (build-graph entities)
        initial-state {:queue (conj clojure.lang.PersistentQueue/EMPTY [entity-id 0])
                       :visited {entity-id 0}}
        {:keys [visited]} (loop [{:keys [queue visited] :as state} initial-state]
                            (if (empty? queue)
                              state
                              (let [[[current depth] next-queue] [(peek queue) (pop queue)]]
                                (if (>= depth hops)
                                  (recur (assoc state :queue next-queue))
                                  (let [neighbors (remove #(contains? visited %)
                                                          (get graph current))
                                        updated-queue (reduce #(conj %1 [%2 (inc depth)]) next-queue neighbors)
                                        updated-visited (reduce #(assoc %1 %2 (inc depth)) visited neighbors)]
                                    (recur {:queue updated-queue :visited updated-visited}))))))]
    {:start (brief start)
     :related (->> visited
                   (remove (fn [[candidate-id _]] (= candidate-id entity-id)))
                   (map (fn [[candidate-id distance]]
                          (assoc (brief (get by-id candidate-id))
                                 :distance distance)))
                   (sort-by (fn [entity]
                              [(:distance entity)
                               (- (or (created-at-ms (get by-id (:entity/id entity))) 0))]))
                   (take limit)
                   vec)}))

(defn get-symbol-memory
  [conn {:keys [symbol-id limit]
         :or {limit 12}}]
  (let [db-value (store/db conn)
        symbol (store/pull-entity-by-id db-value symbol-id)
        related (->> (:related (find-related-context conn {:entity-id symbol-id
                                                           :limit (* 2 limit)
                                                           :hops 2}))
                     (map #(store/pull-entity-by-id db-value (:entity/id %)))
                     (remove nil?)
                     sort-desc)
        grouped (group-briefs (filter #(contains? #{:error :patch :decision :note :task :observation :link :event}
                                                  (:entity/type %))
                                      related))]
    {:symbol (brief symbol)
     :file (some-> symbol :symbol/file brief)
     :related grouped}))

(defn get-task-timeline
  [conn {:keys [task-id]}]
  (let [db-value (store/db conn)
        task (store/pull-entity-by-id db-value task-id)
        project-id (entity-project-id task)
        matches (->> (store/project-entities db-value project-id)
                     (filter (fn [entity]
                               (and (contains? schema/timeline-types (:entity/type entity))
                                    (or (= task-id (:entity/id entity))
                                        (= task-id (get-in entity [:entity/task :entity/id]))
                                        (contains? (direct-ref-ids entity) task-id)))))
                     sort-asc)]
    {:task (brief task)
     :timeline (mapv brief matches)}))

(defn prior-decisions-for-task
  [conn task-id]
  (let [db-value (store/db conn)
        task (store/pull-entity-by-id db-value task-id)
        touched-files (->> (:task/touched-files task)
                           (map :entity/id)
                           set)
        task-created (created-at-ms task)]
    (->> (store/project-entities db-value (entity-project-id task))
         (filter #(= :decision (:entity/type %)))
         (filter (fn [decision]
                   (let [decision-files (->> (:decision/related-files decision) (map :entity/id) set)
                         decision-created (created-at-ms decision)]
                     (and (seq (set/intersection touched-files decision-files))
                          (< (or decision-created 0) task-created)))))
         sort-desc
         (mapv brief))))

(defn context-for-file
  [conn file-id]
  (let [db-value (store/db conn)
        file-entity (store/pull-entity-by-id db-value file-id)
        related (find-related-context conn {:entity-id file-id :limit 12 :hops 2})]
    {:file (brief file-entity)
     :context-to-load (->> (:related related)
                           (filter #(contains? #{:symbol :decision :patch :error :note :task}
                                               (:entity/type %)))
                           vec)}))

(defn hybrid-context
  [conn {:keys [project-id query anchor-id limit]
         :or {limit 8}}]
  (let [db-value (store/db conn)
        search-hits (store/search-body db-value query {:limit limit
                                                       :predicate #(= project-id (entity-project-id %))})
        graph-hits (if anchor-id
                     (map #(store/pull-entity-by-id db-value (:entity/id %))
                          (:related (find-related-context conn {:entity-id anchor-id
                                                                :limit limit
                                                                :hops 2})))
                     [])
        combined (->> (concat search-hits graph-hits)
                      (remove nil?)
                      store/distinct-summaries)]
    {:query query
     :project-id project-id
     :results (mapv brief (take limit combined))}))
