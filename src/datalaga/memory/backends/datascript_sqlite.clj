(ns datalaga.memory.backends.datascript-sqlite
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datascript.core :as d]
            [datascript.storage.sql.core :as storage-sql]
            [datalaga.memory.backend :as backend]
            [datalaga.memory.schema :as schema])
  (:import [org.sqlite SQLiteDataSource]
           [java.sql SQLException]))

(def ^:private body-table "datalaga_entity_body")
(def ^:private body-fts-table "datalaga_entity_body_fts")

(defn- sqlite-url
  [db-path]
  (str "jdbc:sqlite:" (.getAbsolutePath (io/file db-path))))

(defn- make-datasource
  [db-path]
  (when-let [parent (.getParentFile (io/file db-path))]
    (.mkdirs parent))
  (doto (SQLiteDataSource.)
    (.setUrl (sqlite-url db-path))))

(defn- datascript-schema
  []
  (into {}
        (keep (fn [[attr attr-schema]]
                (let [supported (cond-> {}
                                  (:db/cardinality attr-schema)
                                  (assoc :db/cardinality (:db/cardinality attr-schema))

                                  (:db/unique attr-schema)
                                  (assoc :db/unique (:db/unique attr-schema))

                                  (contains? #{:db.type/ref :db.type/tuple} (:db/valueType attr-schema))
                                  (assoc :db/valueType (:db/valueType attr-schema)))]
                  (when (seq supported)
                    [attr supported]))))
        schema/schema))

(defn- ensure-fulltext-schema!
  [datasource]
  (with-open [conn (.getConnection datasource)
              stmt (.createStatement conn)]
    (.execute stmt (str "CREATE TABLE IF NOT EXISTS " body-table
                        " (entity_id TEXT PRIMARY KEY, body TEXT NOT NULL)"))
    (.execute stmt (str "CREATE VIRTUAL TABLE IF NOT EXISTS " body-fts-table
                        " USING fts5(entity_id, body, tokenize = 'unicode61')"))))

(defn- restore-or-create-conn
  [storage]
  (or (try
        (d/restore-conn storage)
        (catch Throwable _
          nil))
      (d/create-conn (datascript-schema) {:storage storage})))

(defn- current-body
  [db-value entity-id]
  (some-> (d/pull db-value [:entity/id :entity/body] [:entity/id entity-id])
          :entity/body))

(defn- sync-fulltext!
  [datasource db-value entity-ids]
  (let [entity-ids (->> entity-ids distinct vec)]
    (when (seq entity-ids)
      (with-open [conn (.getConnection datasource)
                  delete-body (.prepareStatement conn (str "DELETE FROM " body-table " WHERE entity_id = ?"))
                  delete-fts (.prepareStatement conn (str "DELETE FROM " body-fts-table " WHERE entity_id = ?"))
                  insert-body (.prepareStatement conn (str "INSERT INTO " body-table " (entity_id, body) VALUES (?, ?)"))
                  insert-fts (.prepareStatement conn (str "INSERT INTO " body-fts-table " (entity_id, body) VALUES (?, ?)"))]
        (.setAutoCommit conn false)
        (try
          (doseq [entity-id entity-ids]
            (.setString delete-body 1 entity-id)
            (.executeUpdate delete-body)
            (.setString delete-fts 1 entity-id)
            (.executeUpdate delete-fts)
            (when-let [body (some-> (current-body db-value entity-id) str/trim not-empty)]
              (.setString insert-body 1 entity-id)
              (.setString insert-body 2 body)
              (.executeUpdate insert-body)
              (.setString insert-fts 1 entity-id)
              (.setString insert-fts 2 body)
              (.executeUpdate insert-fts)))
          (.commit conn)
          (catch Throwable t
            (.rollback conn)
            (throw t)))))))

(defn- run-search
  [conn sql bind-value limit]
  (with-open [stmt (.prepareStatement conn sql)]
    (.setString stmt 1 bind-value)
    (.setLong stmt 2 limit)
    (with-open [rs (.executeQuery stmt)]
      (loop [acc []]
        (if (.next rs)
          (recur (conj acc (.getString rs 1)))
          acc)))))

(defn- search-entity-ids
  [datasource query limit]
  (with-open [conn (.getConnection datasource)]
    (let [tokens (->> (re-seq #"[\p{L}\p{N}_./:-]+" (or query ""))
                      (remove str/blank?)
                      vec)
          fts-query (if (seq tokens)
                      (str/join " OR " tokens)
                      query)
          like-query (if (seq tokens)
                       (str "%" (str/join "%" tokens) "%")
                       (str "%" query "%"))
          like-sql (str "SELECT entity_id FROM " body-table
                        " WHERE body LIKE ?"
                        " ORDER BY entity_id"
                        " LIMIT ?")]
      (try
        (let [results (run-search conn
                                  (str "SELECT entity_id FROM " body-fts-table
                                       " WHERE " body-fts-table " MATCH ?"
                                       " ORDER BY bm25(" body-fts-table "), entity_id"
                                       " LIMIT ?")
                                  fts-query
                                  limit)]
          (if (seq results)
            results
            (run-search conn like-sql like-query limit)))
        (catch SQLException _
          (run-search conn like-sql like-query limit))))))

(defrecord DatascriptSqliteSnapshot [datasource db]
  backend/SnapshotBackend
  (query-snapshot [_ query-form inputs]
    (apply d/q query-form db inputs))
  (pull-snapshot [_ pull-pattern entity-ref]
    (d/pull db pull-pattern entity-ref))
  (search-body-snapshot [_ query {:keys [limit predicate]
                                  :or {limit 10
                                       predicate (constantly true)}}]
    (if (str/blank? query)
      []
      (->> (search-entity-ids datasource query limit)
           (map #(d/pull db schema/readable-pull [:entity/id %]))
           (remove nil?)
           (filter predicate)
           vec))))

(defrecord DatascriptSqliteConn [datasource storage conn]
  backend/ConnectionBackend
  (backend-key [_] :datascript-sqlite)
  (backend-capabilities [_] #{:datalog-query :pull :fulltext :sqlite :experimental})
  (close-connection! [_]
    nil)
  (snapshot [_]
    (->DatascriptSqliteSnapshot datasource @conn))
  (transact-tx! [_ tx-data]
    (d/transact! conn tx-data)
    (sync-fulltext! datasource @conn (keep :entity/id tx-data))))

(defn open-conn
  [db-path]
  (let [datasource (make-datasource db-path)
        _ (ensure-fulltext-schema! datasource)
        storage (storage-sql/make datasource {:dbtype :sqlite})
        conn (restore-or-create-conn storage)]
    (->DatascriptSqliteConn datasource storage conn)))
