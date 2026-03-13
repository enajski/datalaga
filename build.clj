(ns build
  "Build script for datalaga CLI uberjar and native image preparation.

  Usage:
    clj -T:build clean     ;; remove target/
    clj -T:build uber      ;; build AOT-compiled uberjar
    clj -T:build uber+run  ;; build jar and print a quick smoke-test"
  (:require [clojure.tools.build.api :as b]))

(def lib 'pl.enajski/datalaga)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file "target/datalaga.jar")
(def basis (delay (b/create-basis {:project "deps.edn"
                                   :aliases [:native-image]})))

(defn clean
  "Delete the target directory."
  [_]
  (b/delete {:path "target"}))

(defn uber
  "Build an AOT-compiled uberjar suitable for native-image compilation
  or standalone JVM execution."
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis      @basis
                  :ns-compile '[datalaga.cli]
                  :class-dir  class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'datalaga.cli})
  (println (str "Uberjar written to " uber-file)))
