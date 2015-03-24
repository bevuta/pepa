(ns pepa.authorization
  (:require [pepa.log :as log])
  (:import pepa.db.Database))

(defprotocol AccessFilter
  ;; NOTE: The second arg can either be a list of maps containing an
  ;; :id key or just an id. Implementations must handle this.
  ;; TODO(mu): Provide wrappers to sanitize this?
  (filter-files     [filter files])
  (filter-documents [filter documents])
  (filter-pages     [filter pages])
  (filter-tags      [filter tags])
  ;; TODO: `filter-inbox'?
  )


;;; Helper Functions

(defn filter-file [filter file]
  (first (filter-files filter [file])))

(defn filter-document [filter document]
  (first (filter-documents filter [document])))

(defn filter-page [filter page]
  (first (filter-pages filter [page])))

(defn filter-tag [filter tag]
  (first (filter-tags filter [tag])))

;;; DB Restriction

(defn restrict-db [db filter]
  (assert (satisfies? AccessFilter filter))
  (with-meta db {::filter filter}))

(defn db-filter [db]
  (::filter (meta db)))

(def null-filter
  "A filter that allows everything."
  (reify AccessFilter
    (filter-files     [_ files] files)
    (filter-documents [_ documents] documents)
    (filter-pages     [_ pages] pages)
    (filter-tags      [_ tags] tags)))

;;; Extend pepa.db.Database to delegate to the filter. That allows to
;;; just run `filter-*' like: `(filter-files db ...)'.
(extend-type Database
  AccessFilter
  (filter-files     [db files]
    (filter-files (db-filter db) files))
  (filter-documents [db documents]
    (filter-documents (db-filter db) documents))
  (filter-pages     [db pages]
    (filter-pages (db-filter db) pages))
  (filter-tags      [db tags]
    (filter-tags (db-filter db) tags)))

;;; Ring/Liberator Helpers

(defn wrap-authorization-warnings
  "Ring middleware that logs a warning if the request contains a
  database which isn't restricted via `restrict-db'."
  [handler web]
  (fn [req]
    (let [filter (db-filter (:db web))]
      (when-not filter
        (log/error web "Got HTTP request with unrestricted DB:" req))
      (handler req))))

(defn ^:private validation-fn [entity]
  (case entity
    ;; Singular
    :file filter-file
    :document filter-document
    :page filter-page
    :tag filter-tag
    ;; Plural
    :files (comp seq filter-files)
    :documents (comp seq filter-documents)
    :pages (comp seq filter-pages)
    :tags (comp seq filter-tags)))

;;; TODO(mu): We need to handle PUT/POST here too.
(defn authorization-fn [web entity key]
  (let [validation-fn (validation-fn entity)]
    (fn [ctx]
      (let [db (:db web)
            value (get ctx key)]
        (log/debug web "Validating" entity (str "(" value ")"))
        (let [result (validation-fn db value)]
          (cond
            ;; If the input is a list and the original value is empty,
            ;; the request is allowed unconditionally
            (and (sequential? value) (empty? value))
            true
            
            ;; If value is a list of entities & result has less
            ;; entities, forbid request completely
            (and (sequential? value)
                 (or (nil? result) (< (count result) (count value))))
            (do
              (log/warn web "Client tried to access *some* entities he wasn't allowed to"
                        (str "(" entity "):") (set (remove (set result) value)))
              false)

            result
            [true {key result}]

            ;; Default case: forbidden
            true
            false))))))
