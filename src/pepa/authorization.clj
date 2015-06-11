(ns pepa.authorization
  (:require [pepa.log :as log]
            pepa.db)
  (:import pepa.db.Database))

(defprotocol AccessFilter
  (-filter-files     [filter files])
  (-filter-documents [filter documents])
  (-filter-pages     [filter pages])
  (-filter-tags      [filter tags])
  ;; TODO: `filter-inbox'?
  )

;;; Filter Functions

(defmacro ^:private defentity [entity]
  (let [filter-singular (symbol (str "filter-" entity))
        filter-plural (symbol (str filter-singular  "s"))
        filter-impl (symbol (str "-" filter-plural))]
    `(do
       ;; Plural Implementation
       (defn ~filter-plural [filter# es#]
         {:pre [(coll? es#)]}
         (when (seq es#)
           ;; If we get an integer as first item, assume we got plain
           ;; IDs everywhere.
           (some->>
            (if (integer? (first es#))
              (~filter-impl filter# es#)
              ;; If it's a map of entities with :id keys, get set of
              ;; valid IDs and remove the others from the original
              ;; sequence.
              (let [ids# (->> es# (map :id) (~filter-impl filter#) set)]
                (filter #(contains? ids# (:id %)) es#)))
            ;; Make sure to return nil if empty
            (seq)
            ;; Return same collection type as given
            (into (empty es#)))))
       ;; Singular Implementation
       (defn ~filter-singular [filter# file#]
         (first (~filter-plural filter# [file#]))))))

(comment
  (defn filter-documents [filter entities]
    {:pre [(coll? entities)]}
    (when (seq entities)
      (if (integer? (first entities))
        (-filter-documents filter entities)
        (let [ids (->> entities
                       (map :id)
                       (-filter-documents filter)
                       set)]
          (filter
           #(contains? ids (:id %))
           entities)))))
  (defn filter-document [filter document]
    (first
     (filter-documents filter [document]))))

(defentity file)
(defentity document)
(defentity page)
(defentity tag)

;;; DB Restriction

(defn restrict-db [db filter]
  {:pre [(satisfies? AccessFilter filter)]}
  (with-meta db {::filter filter}))

(defn db-filter [db]
  (::filter (meta db)))

(defn num-filter [n]
  (let [f (fn [es] (remove #(> % n) es))]
   (reify AccessFilter
     (-filter-files     [_ files]     (f files))
     (-filter-documents [_ documents] (f documents))
     (-filter-pages     [_ pages]     pages)
     (-filter-tags      [_ tags]      tags))))

(def null-filter
  "A filter that allows everything."
  (reify AccessFilter
    (-filter-files     [_ files]     files)
    (-filter-documents [_ documents] documents)
    (-filter-pages     [_ pages]     pages)
    (-filter-tags      [_ tags]      tags)))

;;; Extend pepa.db.Database to delegate to the filter. That allows to
;;; just run `filter-*' like: `(filter-files db ...)'.
(extend-type pepa.db.Database
  AccessFilter
  (-filter-files     [db files]
    (filter-files (db-filter db) files))
  (-filter-documents [db documents]
    (filter-documents (db-filter db) documents))
  (-filter-pages     [db pages]
    (filter-pages (db-filter db) pages))
  (-filter-tags      [db tags]
    (filter-tags (db-filter db) tags)))

;;; Ring/Liberator Helpers

(defn entity-filter-fn
  "Returns the correct filter-fn for ENTITY. Throws for invalid entities."
  [entity]
  (case entity
    ;; Singular
    :file       filter-file
    :document   filter-document
    :page       filter-page
    :tag        filter-tag
    ;; Plural
    :files      filter-files
    :documents  filter-documents
    :pages      filter-pages
    :tags       filter-tags))

(defn wrap-authorization-warnings
  "Ring middleware that logs a warning if the request contains a
  database which isn't restricted via `restrict-db'."
  [handler]
  (fn [req]
    (when-not (db-filter (get-in req [:pepa/web :db]))
      (log/warn (:pepa/web req) "Got HTTP request with unrestricted DB:" req))
    (handler req)))

(defn wrap-db-filter [handler filter-fn]
  {:pre [(fn? filter-fn)]}
  (fn [req]
    (assert (:pepa/web req))
    (handler (update-in req [:pepa/web :db] #(restrict-db % (filter-fn req))))))



;;; TODO(mu): We need to handle PUT/POST here too.
(defn authorization-fn [web entity key]
  (let [filter-fn (entity-filter-fn entity)]
    (fn [ctx]
      (let [db (:db web)
            value (get ctx key)]
        (log/debug web "Validating" entity (str "(" value ")"))
        (let [result (filter-fn db value)]
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
