(ns datalaga.test-support
  (:require [clojure.java.io :as io]
            [datalaga.memory.store :as store]))

(def test-backends
  [:datalevin
   :datascript-sqlite])

(defn delete-path!
  [path]
  (let [root (io/file path)]
    (when (.exists root)
      (if (.isDirectory root)
        (doseq [f (reverse (file-seq root))]
          (io/delete-file f true))
        (io/delete-file root true)))))

(defn- temp-root
  [prefix]
  (.getAbsolutePath
   (.toFile
    (java.nio.file.Files/createTempDirectory
     prefix
     (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- backend-db-path
  [root backend]
  (case (store/normalize-backend backend)
    :datascript-sqlite (str root "/memory.sqlite")
    (str root "/memory")))

(defn with-temp-conn
  ([f]
   (with-temp-conn store/default-backend f))
  ([backend f]
   (let [root (temp-root "datalaga-test-db")
         db-path (backend-db-path root backend)
         conn (store/open-conn {:backend backend :db-path db-path})]
     (try
       (f conn db-path)
       (finally
         (store/close! conn)
         (delete-path! root))))))
