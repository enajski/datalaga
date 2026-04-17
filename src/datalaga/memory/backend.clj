(ns datalaga.memory.backend
  (:require [clojure.string :as str]))

(def default-backend :datalevin)
(def default-db-path ".data/memory")
(def default-datascript-sqlite-path ".data/memory.sqlite")

(def supported-backends
  #{:datalevin
    :datascript-sqlite})

(defprotocol ConnectionBackend
  (backend-key [conn])
  (backend-capabilities [conn])
  (close-connection! [conn])
  (snapshot [conn])
  (transact-tx! [conn tx-data]))

(defprotocol SnapshotBackend
  (query-snapshot [db-value query-form inputs])
  (pull-snapshot [db-value pull-pattern entity-ref])
  (search-body-snapshot [db-value query opts]))

(defn normalize-backend
  [value]
  (let [backend (cond
                  (keyword? value) value
                  (string? value) (-> value
                                      str/trim
                                      str/lower-case
                                      (str/replace "_" "-")
                                      keyword)
                  (nil? value) default-backend
                  :else (throw (ex-info "Unsupported backend value"
                                        {:backend value
                                         :supported_backends (mapv name (sort supported-backends))})))]
    (when-not (contains? supported-backends backend)
      (throw (ex-info "Unsupported backend"
                      {:backend backend
                       :supported_backends (mapv name (sort supported-backends))})))
    backend))

(defn default-db-path-for
  [backend]
  (case (normalize-backend backend)
    :datascript-sqlite default-datascript-sqlite-path
    default-db-path))
