(ns datalevin-mcp.util
  (:require [clojure.string :as str])
  (:import (java.time Instant ZoneOffset)
           (java.time.format DateTimeFormatter)
           (java.util Date UUID)))

(def iso-formatter
  (.withZone DateTimeFormatter/ISO_OFFSET_DATE_TIME ZoneOffset/UTC))

(defn now-date []
  (Date/from (Instant/now)))

(defn now-iso []
  (.format iso-formatter (.toInstant (now-date))))

(defn ->date [value]
  (cond
    (nil? value) nil
    (instance? Date value) value
    (instance? Instant value) (Date/from value)
    (string? value) (Date/from (Instant/parse value))
    :else (throw (ex-info "Unsupported instant value" {:value value}))))

(defn ->keyword [value]
  (cond
    (nil? value) nil
    (keyword? value) value
    (string? value)
    (let [trimmed (str/trim value)]
      (if (str/includes? trimmed "/")
        (keyword (namespace (symbol trimmed)) (name (symbol trimmed)))
        (keyword trimmed)))
    :else (throw (ex-info "Expected keyword-compatible value" {:value value}))))

(defn ->string [value]
  (cond
    (nil? value) nil
    (keyword? value) (name value)
    :else (str value)))

(defn uuid-str []
  (str (UUID/randomUUID)))

(defn ensure-vector [value]
  (cond
    (nil? value) []
    (vector? value) value
    (sequential? value) (vec value)
    :else [value]))

(defn compact-map [m]
  (into {}
        (remove (comp nil? val))
        m))

(defn keywordize-keys-shallow [m]
  (into {}
        (map (fn [[k v]]
               [(if (string? k) (->keyword k) k) v]))
        m))

(defn distinct-by
  [f coll]
  (vals
   (reduce (fn [acc item]
             (assoc acc (f item) item))
           {}
           coll)))

(defn contains-text?
  [haystack needle]
  (and (string? haystack)
       (string? needle)
       (str/includes? (str/lower-case haystack) (str/lower-case needle))))

(defn token-set [text]
  (if (str/blank? text)
    #{}
    (->> (str/split (str/lower-case text) #"[^\p{Alnum}:/#._-]+")
         (remove str/blank?)
         set)))

(defn keyword->json-key [value]
  (if-let [ns-name (namespace value)]
    (str ns-name "/" (name value))
    (name value)))

(defn json-friendly [value]
  (cond
    (nil? value) nil
    (instance? Date value) (.format iso-formatter (.toInstant value))
    (keyword? value) (keyword->json-key value)
    (map? value)
    (into {}
          (map (fn [[k v]]
                 [(if (keyword? k) (keyword->json-key k) k)
                  (json-friendly v)]))
          value)
    (set? value) (mapv json-friendly value)
    (sequential? value) (mapv json-friendly value)
    :else value))
