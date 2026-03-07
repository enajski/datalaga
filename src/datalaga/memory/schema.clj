(ns datalaga.memory.schema)

(def schema
  {:entity/id {:db/valueType :db.type/string
               :db/unique :db.unique/identity
               :db/doc "Stable identity used by MCP tools and seed data."}
   :entity/type {:db/valueType :db.type/keyword}
   :entity/name {:db/valueType :db.type/string}
   :entity/status {:db/valueType :db.type/keyword}
   :entity/path {:db/valueType :db.type/string}
   :entity/kind {:db/valueType :db.type/keyword}
   :entity/language {:db/valueType :db.type/keyword}
   :entity/body {:db/valueType :db.type/string
                 :db/fulltext true
                 :db/doc "Normalized searchable text for full-text retrieval."}
   :entity/summary {:db/valueType :db.type/string}
   :entity/source {:db/valueType :db.type/string}
   :entity/source-ref {:db/valueType :db.type/string}
   :entity/external-refs {:db/valueType :db.type/string
                          :db/cardinality :db.cardinality/many}
   :entity/created-at {:db/valueType :db.type/instant}
   :entity/updated-at {:db/valueType :db.type/instant}
   :entity/tags {:db/valueType :db.type/string
                 :db/cardinality :db.cardinality/many}
   :entity/project {:db/valueType :db.type/ref}
   :entity/session {:db/valueType :db.type/ref}
   :entity/task {:db/valueType :db.type/ref}
   :entity/file {:db/valueType :db.type/ref}
   :entity/symbol {:db/valueType :db.type/ref}
   :entity/refs {:db/valueType :db.type/ref
                 :db/cardinality :db.cardinality/many}

   :project/root {:db/valueType :db.type/string}

   :file/module {:db/valueType :db.type/string}
   :file/contains-symbols {:db/valueType :db.type/ref
                           :db/cardinality :db.cardinality/many}

   :symbol/file {:db/valueType :db.type/ref}
   :symbol/qualified-name {:db/valueType :db.type/string}
   :symbol/signature {:db/valueType :db.type/string}
   :symbol/kind {:db/valueType :db.type/keyword}

   :task/description {:db/valueType :db.type/string}
   :task/priority {:db/valueType :db.type/keyword}
   :task/touched-files {:db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many}
   :task/related-symbols {:db/valueType :db.type/ref
                          :db/cardinality :db.cardinality/many}

   :session/agent {:db/valueType :db.type/string}
   :session/started-at {:db/valueType :db.type/instant}
   :session/ended-at {:db/valueType :db.type/instant}

   :tool-run/command {:db/valueType :db.type/string}
   :tool-run/exit-code {:db/valueType :db.type/long}
   :tool-run/output {:db/valueType :db.type/string}
   :tool-run/phase {:db/valueType :db.type/keyword}
   :tool-run/touched-files {:db/valueType :db.type/ref
                            :db/cardinality :db.cardinality/many}
   :tool-run/supersedes {:db/valueType :db.type/ref
                         :db/cardinality :db.cardinality/many}
   :tool-run/retries-of {:db/valueType :db.type/ref
                         :db/cardinality :db.cardinality/many}

   :error/tool-run {:db/valueType :db.type/ref}
   :error/category {:db/valueType :db.type/keyword}
   :error/details {:db/valueType :db.type/string}
   :error/related-symbols {:db/valueType :db.type/ref
                           :db/cardinality :db.cardinality/many}

   :observation/tool-run {:db/valueType :db.type/ref}
   :observation/confidence {:db/valueType :db.type/long}
   :observation/detail {:db/valueType :db.type/string}

   :decision/rationale {:db/valueType :db.type/string}
   :decision/outcome {:db/valueType :db.type/string}
   :decision/alternatives {:db/valueType :db.type/string
                           :db/cardinality :db.cardinality/many}
   :decision/related-files {:db/valueType :db.type/ref
                            :db/cardinality :db.cardinality/many}
   :decision/related-symbols {:db/valueType :db.type/ref
                              :db/cardinality :db.cardinality/many}

   :patch/commit {:db/valueType :db.type/string}
   :patch/diff-summary {:db/valueType :db.type/string}
   :patch/modified-files {:db/valueType :db.type/ref
                          :db/cardinality :db.cardinality/many}
   :patch/modified-symbols {:db/valueType :db.type/ref
                            :db/cardinality :db.cardinality/many}
   :patch/decision {:db/valueType :db.type/ref}
   :patch/tool-run {:db/valueType :db.type/ref}

   :note/content {:db/valueType :db.type/string}
   :note/refers-to {:db/valueType :db.type/ref
                    :db/cardinality :db.cardinality/many}

   :event/kind {:db/valueType :db.type/keyword}
   :event/at {:db/valueType :db.type/instant}
   :event/session {:db/valueType :db.type/ref}
   :event/subjects {:db/valueType :db.type/ref
                    :db/cardinality :db.cardinality/many}

   :link/from {:db/valueType :db.type/ref}
   :link/to {:db/valueType :db.type/ref}
   :link/type {:db/valueType :db.type/keyword}
   :link/explanation {:db/valueType :db.type/string}
   :link/evidence {:db/valueType :db.type/ref
                   :db/cardinality :db.cardinality/many}})

(def ref-attrs
  #{:entity/project
    :entity/session
    :entity/task
    :entity/file
    :entity/symbol
    :entity/refs
    :file/contains-symbols
    :symbol/file
    :task/touched-files
    :task/related-symbols
    :tool-run/touched-files
    :tool-run/supersedes
    :tool-run/retries-of
    :error/tool-run
    :error/related-symbols
    :observation/tool-run
    :decision/related-files
    :decision/related-symbols
    :patch/modified-files
    :patch/modified-symbols
    :patch/decision
    :patch/tool-run
    :note/refers-to
    :event/session
    :event/subjects
    :link/from
    :link/to
    :link/evidence})

(def many-attrs
  #{:entity/tags
    :entity/external-refs
    :entity/refs
    :file/contains-symbols
    :task/touched-files
    :task/related-symbols
    :tool-run/touched-files
    :tool-run/supersedes
    :tool-run/retries-of
    :error/related-symbols
    :decision/alternatives
    :decision/related-files
    :decision/related-symbols
    :patch/modified-files
    :patch/modified-symbols
    :note/refers-to
    :event/subjects
    :link/evidence})

(def entity-types
  #{:project
    :file
    :symbol
    :task
    :observation
    :decision
    :patch
    :error
    :tool-run
    :session
    :event
    :note
    :link})

(def keyword-valued-attrs
  #{:entity/type
    :entity/status
    :entity/kind
    :entity/language
    :symbol/kind
    :task/priority
    :tool-run/phase
    :error/category
    :event/kind
    :link/type})

(def instant-attrs
  #{:entity/created-at
    :entity/updated-at
    :session/started-at
    :session/ended-at
    :event/at})

(def timeline-types
  #{:task :tool-run :error :observation :decision :patch :note :event})

(def readable-pull
  [:db/id
   :entity/id
   :entity/type
   :entity/name
   :entity/status
   :entity/path
   :entity/kind
   :entity/language
   :entity/body
   :entity/summary
   :entity/source
   :entity/source-ref
   :entity/external-refs
   :entity/created-at
   :entity/updated-at
   :entity/tags
   :project/root
   :file/module
   :symbol/qualified-name
   :symbol/signature
   :symbol/kind
   :task/description
   :task/priority
   :session/agent
   :session/started-at
   :session/ended-at
   :tool-run/command
   :tool-run/exit-code
   :tool-run/output
   :tool-run/phase
   :tool-run/supersedes
   :tool-run/retries-of
   :error/category
   :error/details
   :observation/confidence
   :observation/detail
   :decision/rationale
   :decision/outcome
   :decision/alternatives
   :patch/commit
   :patch/diff-summary
   :note/content
   :event/kind
   :event/at
   :link/type
   :link/explanation
   {:entity/project [:entity/id :entity/name]}
   {:entity/session [:entity/id :entity/name]}
   {:entity/task [:entity/id :entity/name]}
   {:entity/file [:entity/id :entity/name :entity/path]}
   {:entity/symbol [:entity/id :entity/name]}
   {:entity/refs [:entity/id :entity/type :entity/name]}
   {:file/contains-symbols [:entity/id :entity/name]}
   {:symbol/file [:entity/id :entity/name :entity/path]}
   {:task/touched-files [:entity/id :entity/name :entity/path]}
   {:task/related-symbols [:entity/id :entity/name]}
   {:tool-run/touched-files [:entity/id :entity/name :entity/path]}
   {:tool-run/supersedes [:entity/id :entity/type :entity/name]}
   {:tool-run/retries-of [:entity/id :entity/type :entity/name]}
   {:error/tool-run [:entity/id :entity/name]}
   {:error/related-symbols [:entity/id :entity/name]}
   {:observation/tool-run [:entity/id :entity/name]}
   {:decision/related-files [:entity/id :entity/name :entity/path]}
   {:decision/related-symbols [:entity/id :entity/name]}
   {:patch/modified-files [:entity/id :entity/name :entity/path]}
   {:patch/modified-symbols [:entity/id :entity/name]}
   {:patch/decision [:entity/id :entity/name]}
   {:patch/tool-run [:entity/id :entity/name]}
   {:note/refers-to [:entity/id :entity/type :entity/name :entity/path]}
   {:event/session [:entity/id :entity/name]}
   {:event/subjects [:entity/id :entity/type :entity/name]}
   {:link/from [:entity/id :entity/type :entity/name]}
   {:link/to [:entity/id :entity/type :entity/name]}
   {:link/evidence [:entity/id :entity/type :entity/name]}])
