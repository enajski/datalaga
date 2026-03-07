(ns datalaga.memory.queries
  (:require [clojure.string :as str]
            [datalevin.core :as d]
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

(defn- entity-project-id
  [entity]
  (or (get-in entity [:entity/project :entity/id])
      (when (= :project (:entity/type entity))
        (:entity/id entity))))

(defn- brief
  [entity]
  (store/entity-brief entity))

(defn- normalize-project-scope
  [db-value project-id project-ids]
  (let [explicit (->> (concat (util/ensure-vector project-ids)
                              (when project-id [project-id]))
                      (remove nil?)
                      distinct
                      vec)]
    (if (seq explicit)
      explicit
      (vec (store/all-project-ids db-value)))))

(defn- scoped-project-eids
  [db-value project-ids]
  (->> project-ids
       (mapv (fn [project-id]
               [project-id
                (store/require-project-eid db-value project-id)]))
       (into {})))

(defn- scoped-entity-ids
  [db-value project-id->eid]
  (->> (concat
        (keys project-id->eid)
        (mapcat (fn [[_ project-eid]]
                  (store/project-entity-ids-for-eid db-value project-eid))
                project-id->eid))
       distinct
       vec))

(defn- scoped-edge-pairs
  [db-value project-id->eid]
  (->> schema/ref-attrs
       (mapcat (fn [attr]
                 (mapcat (fn [[_ project-eid]]
                           (store/project-ref-edge-pairs db-value project-eid attr))
                         project-id->eid)))
       distinct
       vec))

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

(defn- require-eid
  [db-value entity-id entity-label]
  (or (store/lookup-eid db-value entity-id)
      (throw (ex-info (str (str/capitalize entity-label) " does not exist")
                      {(keyword (str entity-label "-id")) entity-id}))))

(defn- scoped-type-entity-ids
  [db-value project-ids entity-type]
  (let [project-id->eid (scoped-project-eids db-value project-ids)]
    (->> project-id->eid
         vals
         (mapcat #(store/project-entity-ids-by-type db-value % entity-type))
         distinct
         vec)))

(defn- exact-match-entity-ids
  [db-value project-id term]
  (let [target-lower (some-> term str/lower-case)
        project-eid (when project-id
                      (store/project-eid db-value project-id))
        attrs [:entity/id :entity/name :entity/path :symbol/qualified-name]]
    (if (str/blank? target-lower)
      []
      (->> attrs
           (mapcat (fn [attr]
                     (if project-eid
                       (d/q '[:find [?entity-id ...]
                              :in $ ?project ?attr ?target-lower
                              :where
                              [?e :entity/project ?project]
                              [?e ?attr ?value]
                              [(clojure.string/lower-case ?value) ?value-lower]
                              [(= ?value-lower ?target-lower)]
                              [?e :entity/id ?entity-id]]
                            db-value
                            project-eid
                            attr
                            target-lower)
                       (d/q '[:find [?entity-id ...]
                              :in $ ?attr ?target-lower
                              :where
                              [?e ?attr ?value]
                              [(clojure.string/lower-case ?value) ?value-lower]
                              [(= ?value-lower ?target-lower)]
                              [?e :entity/id ?entity-id]]
                            db-value
                            attr
                            target-lower))))
           distinct
           vec))))

(defn exact-lookup
  [conn {:keys [project-id term limit]
         :or {limit 10}}]
  (let [db-value (store/db conn)
        matched-ids (exact-match-entity-ids db-value project-id term)]
    (->> (store/pull-entities-by-id db-value matched-ids)
         sort-desc
         (take limit)
         vec)))

(defn project-summary
  [conn project-id]
  (let [db-value (store/db conn)
        project-eid (require-eid db-value project-id "project")
        counts-by-type (d/q '[:find ?entity-type (count ?e)
                              :in $ ?project
                              :where
                              [?e :entity/project ?project]
                              [?e :entity/type ?entity-type]]
                            db-value
                            project-eid)
        counts (assoc (into {} (map (fn [[entity-type c]] [entity-type c]) counts-by-type))
                      :project 1)
        recent-patches (->> (store/project-entity-ids-by-type db-value project-eid :patch)
                            (store/pull-entities-by-id db-value)
                            sort-desc
                            (take 5)
                            (mapv brief))
        recent-failures (->> (store/project-entity-ids-by-type db-value project-eid :error)
                             (store/pull-entities-by-id db-value)
                             (filter unresolved-error?)
                             sort-desc
                             (take 5)
                             (mapv brief))
        active-tasks (->> (store/project-entity-ids-by-type db-value project-eid :task)
                          (store/pull-entities-by-id db-value)
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
  (let [db-value (store/db conn)
        project-eid (require-eid db-value project-id "project")]
    (->> (store/project-entity-ids-by-type db-value project-eid :error)
         (store/pull-entities-by-id db-value)
         (filter unresolved-error?)
         sort-desc
         (mapv brief))))

(defn active-tasks
  [conn project-id]
  (let [db-value (store/db conn)
        project-eid (require-eid db-value project-id "project")]
    (->> (store/project-entity-ids-by-type db-value project-eid :task)
         (store/pull-entities-by-id db-value)
         (filter active-task?)
         sort-desc
         (mapv brief))))

(defn session-timeline
  [conn session-id]
  (let [db-value (store/db conn)
        session (store/pull-entity-by-id db-value session-id)
        _ (when-not session
            (throw (ex-info "Session does not exist" {:session-id session-id})))
        project-id (entity-project-id session)
        project-eid (require-eid db-value project-id "project")
        session-eid (require-eid db-value session-id "session")
        entity-ids (d/q '[:find [?entity-id ...]
                          :in $ ?project ?session ?timeline-types
                          :where
                          [?e :entity/project ?project]
                          [?e :entity/session ?session]
                          [?e :entity/type ?entity-type]
                          [(contains? ?timeline-types ?entity-type)]
                          [?e :entity/id ?entity-id]]
                        db-value
                        project-eid
                        session-eid
                        schema/timeline-types)]
    {:session (brief session)
     :entries (->> (store/pull-entities-by-id db-value entity-ids)
                   sort-asc
                   (mapv brief))}))

(defn search-notes
  [conn {:keys [project-id project-ids query limit]
         :or {limit 5}}]
  (let [db-value (store/db conn)
        scope (normalize-project-scope db-value project-id project-ids)
        scoped-note-id-set (->> (scoped-type-entity-ids db-value scope :note)
                                set)]
    {:project-id project-id
     :project_ids scope
     :query query
     :matches (->> (store/search-body db-value query {:limit (* 3 limit)
                                                      :predicate #(and (= :note (:entity/type %))
                                                                       (contains? scoped-note-id-set
                                                                                  (:entity/id %)))})
                   (take limit)
                   (mapv brief))}))

(defn- build-graph
  [entity-ids edge-pairs]
  (let [id-set (set entity-ids)]
    (reduce (fn [graph [source-id target-id]]
              (if (or (= source-id target-id)
                      (not (contains? id-set source-id))
                      (not (contains? id-set target-id)))
                graph
                (-> graph
                    (update source-id (fnil conj #{}) target-id)
                    (update target-id (fnil conj #{}) source-id))))
            (into {} (map (fn [entity-id] [entity-id #{}]) entity-ids))
            edge-pairs)))

(defn find-related-context
  [conn {:keys [entity-id project-ids limit hops]
         :or {limit 8
              hops 2}}]
  (let [db-value (store/db conn)
        start (store/pull-entity-by-id db-value entity-id)
        _ (when-not start
            (throw (ex-info "Entity does not exist" {:entity-id entity-id})))
        project-id (entity-project-id start)
        scope (->> (concat [project-id] (util/ensure-vector project-ids))
                   (remove nil?)
                   distinct
                   vec)
        scoped-projects (scoped-project-eids db-value scope)
        entity-ids (scoped-entity-ids db-value scoped-projects)
        edge-pairs (scoped-edge-pairs db-value scoped-projects)
        graph (build-graph entity-ids edge-pairs)
        initial-state {:queue (conj clojure.lang.PersistentQueue/EMPTY [entity-id 0])
                       :visited {entity-id 0}}
        {:keys [visited]} (loop [{:keys [queue visited] :as state} initial-state]
                            (if (empty? queue)
                              state
                              (let [[[current depth] next-queue] [(peek queue) (pop queue)]]
                                (if (>= depth hops)
                                  (recur (assoc state :queue next-queue))
                                  (let [neighbors (remove #(contains? visited %)
                                                          (get graph current #{}))
                                        updated-queue (reduce #(conj %1 [%2 (inc depth)]) next-queue neighbors)
                                        updated-visited (reduce #(assoc %1 %2 (inc depth)) visited neighbors)]
                                    (recur {:queue updated-queue :visited updated-visited}))))))
        related-entities (->> visited
                              keys
                              (remove #(= % entity-id))
                              (store/pull-entities-by-id db-value))
        by-id (into {} (map (juxt :entity/id identity) (conj related-entities start)))]
    {:start (brief start)
     :project_ids scope
     :related (->> visited
                   (remove (fn [[candidate-id _]] (= candidate-id entity-id)))
                   (map (fn [[candidate-id distance]]
                          (when-let [entity (get by-id candidate-id)]
                            (assoc (brief entity)
                                   :distance distance))))
                   (remove nil?)
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
        _ (when-not symbol
            (throw (ex-info "Symbol does not exist" {:symbol-id symbol-id})))
        related-ids (->> (:related (find-related-context conn {:entity-id symbol-id
                                                               :limit (* 2 limit)
                                                               :hops 2}))
                         (map :entity/id)
                         distinct
                         vec)
        related (->> (store/pull-entities-by-id db-value related-ids)
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
        _ (when-not task
            (throw (ex-info "Task does not exist" {:task-id task-id})))
        project-id (entity-project-id task)
        project-eid (store/lookup-eid db-value project-id)
        task-eid (store/lookup-eid db-value task-id)
        timeline-types schema/timeline-types
        self-ids (d/q '[:find [?entity-id ...]
                        :in $ ?project ?task-id ?timeline-types
                        :where
                        [?e :entity/project ?project]
                        [?e :entity/type ?etype]
                        [(contains? ?timeline-types ?etype)]
                        [?e :entity/id ?entity-id]
                        [(= ?entity-id ?task-id)]]
                      db-value
                      project-eid
                      task-id
                      timeline-types)
        scoped-task-ids (d/q '[:find [?entity-id ...]
                               :in $ ?project ?task-eid ?timeline-types
                               :where
                               [?e :entity/project ?project]
                               [?e :entity/type ?etype]
                               [(contains? ?timeline-types ?etype)]
                               [?e :entity/task ?task-eid]
                               [?e :entity/id ?entity-id]]
                             db-value
                             project-eid
                             task-eid
                             timeline-types)
        ref-attrs [:entity/refs :note/refers-to :event/subjects :link/from :link/to :link/evidence]
        ref-ids (->> ref-attrs
                     (mapcat (fn [attr]
                               (d/q '[:find [?entity-id ...]
                                      :in $ ?project ?task-eid ?attr ?timeline-types
                                      :where
                                      [?e :entity/project ?project]
                                      [?e :entity/type ?etype]
                                      [(contains? ?timeline-types ?etype)]
                                      [?e ?attr ?task-eid]
                                      [?e :entity/id ?entity-id]]
                                    db-value
                                    project-eid
                                    task-eid
                                    attr
                                    timeline-types)))
                     distinct)
        matches (->> (concat self-ids scoped-task-ids ref-ids)
                     distinct
                     (store/pull-entities-by-id db-value)
                     sort-asc)]
    {:task (brief task)
     :timeline (mapv brief matches)}))

(defn prior-decisions-for-task
  [conn task-id]
  (let [db-value (store/db conn)
        task (store/pull-entity-by-id db-value task-id)]
    (when-not task
      (throw (ex-info "Task does not exist" {:task-id task-id})))
    (->> (d/q '[:find ?decision-id
                :in $ ?task-id
                :where
                [?task :entity/id ?task-id]
                [?task :entity/project ?project]
                [?task :entity/created-at ?task-created]
                [?task :task/touched-files ?file]
                [?decision :entity/type :decision]
                [?decision :entity/project ?project]
                [?decision :decision/related-files ?file]
                [?decision :entity/id ?decision-id]
                [?decision :entity/created-at ?decision-created]
                [(< ?decision-created ?task-created)]]
              db-value
              task-id)
         (map first)
         distinct
         (store/pull-entities-by-id db-value)
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
        project-eid (require-eid db-value project-id "project")
        scoped-id-set (->> (scoped-entity-ids db-value {project-id project-eid})
                           set)
        search-hits (store/search-body db-value query {:limit limit
                                                       :predicate #(contains? scoped-id-set (:entity/id %))})
        graph-hits (if anchor-id
                     (->> (:related (find-related-context conn {:entity-id anchor-id
                                                                :project-ids [project-id]
                                                                :limit limit
                                                                :hops 2}))
                          (map :entity/id)
                          (store/pull-entities-by-id db-value))
                     [])
        combined (->> (concat search-hits graph-hits)
                      (remove nil?)
                      store/distinct-summaries)]
    {:query query
     :project-id project-id
     :results (mapv brief (take limit combined))}))
