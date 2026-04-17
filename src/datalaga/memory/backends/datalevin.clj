(ns datalaga.memory.backends.datalevin
  (:require [datalevin.core :as d]
            [datalaga.memory.backend :as backend]
            [datalaga.memory.schema :as schema]))

(def datalevin-opts
  {:validate-data? true
   :closed-schema? false})

(defrecord DatalevinSnapshot [db]
  backend/SnapshotBackend
  (query-snapshot [_ query-form inputs]
    (apply d/q query-form db inputs))
  (pull-snapshot [_ pull-pattern entity-ref]
    (d/pull db pull-pattern entity-ref))
  (search-body-snapshot [_ query {:keys [limit predicate]
                                  :or {limit 10
                                       predicate (constantly true)}}]
    (letfn [(entry->eid [entry]
              (cond
                (nil? entry) nil
                (vector? entry) (first entry)
                :else (d/datom-e entry)))]
      (->> (d/fulltext-datoms db query)
           (map entry->eid)
           (remove nil?)
           distinct
           (map #(d/pull db schema/readable-pull %))
           (remove nil?)
           (filter predicate)
           (take limit)
           vec))))

(defrecord DatalevinConn [conn]
  backend/ConnectionBackend
  (backend-key [_] :datalevin)
  (backend-capabilities [_] #{:datalog-query :pull :fulltext})
  (close-connection! [_]
    (d/close conn))
  (snapshot [_]
    (->DatalevinSnapshot (d/db conn)))
  (transact-tx! [_ tx-data]
    (d/transact! conn tx-data)))

(defn open-conn
  [db-path]
  (->DatalevinConn (d/get-conn db-path schema/schema datalevin-opts)))
