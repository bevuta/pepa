(ns pepa.authorization
  (:require [pepa.log :as log])
  (:import [pepa.db Database]))

(defprotocol AccessFilter
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
  [handler]
  (fn [req]
    (let [db (:pepa/db req)]
      (when-not (db-filter db)
        (log/warn db "Got HTTP request with unrestricted DB:"
                  (dissoc req :pepa/web :pepa/bus :pepa/config)))
      (handler (update-in req [:pepa/db] restrict-db
                          (or (db-filter db) null-filter))))))

(defn wrap-filter
  "Ring middleware that adds a given filter to the request. Doesn't
  overwrite existing filters."
  [handler filter]
  (fn [req]
    (handler (update-in req [:pepa/db] restrict-db filter))))

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
(defn authorization-fn [entity key]
  (let [validation-fn (validation-fn entity)]
    (fn [ctx]
      (let [web (get-in ctx [:request :pepa/web])
            db (get-in ctx [:request :pepa/db])
            value (get ctx key)]
        (log/debug web "Validating" entity (str "(" value ")"))
        (let [result (validation-fn db value)]
          (when-not result
            (log/warn web "Client unauthorized to access resource:"
                      (get-in ctx [:request :uri])))
          (boolean result))))))
