(ns pepa.model.query
  (:require [clojure.string :as s]
            [clojure.core.match :refer [match]]))

;; TODO:
;; * perhaps find a way to not parenthesize too much

(defn syntax-error [msg expr]
  (throw (ex-info (str "Query syntax error: " msg) {:expr expr})))

(defn parenthesize [expr]
  (str "(" expr ")"))

(defn parenthesize-coll [exprs]
  (if (seq (rest exprs))
    (map parenthesize exprs)
    exprs))

(defn transpose [m]
  (apply mapv vector m))

(defn expr-type [expr]
  (if (coll? expr)
    (list (first expr))
    expr))

(defmulti ->sql expr-type)

(defn ->infix-sql [operator exprs]
  (let [[sql params] (transpose (map ->sql exprs))]
    [(s/join operator (parenthesize-coll sql))
     (reduce concat params)]))

(defmethod ->sql '(and) [[_ & exprs]]
  (->infix-sql " AND " exprs))

(defmethod ->sql '(or) [[_ & exprs]]
  (->infix-sql " OR " exprs))

(defn ->comparator-sql [operator exprs]
  (case (count exprs)
    (0 1) (syntax-error (str "The " operator " operator needs at least two arguments")
                        (first exprs))
    2 (->infix-sql (str " " operator " ") exprs)
    (->> (partition 2 1 exprs)
         (map (partial cons operator))
         (cons 'and)
         (->sql))))

(defmacro defcomparator [op]
  `(defmethod ->sql '(~op) [[op# & exprs#]]
     (->comparator-sql op# exprs#)))

(defcomparator =)
(defcomparator >)
(defcomparator >=)
(defcomparator <)
(defcomparator <=)
(defcomparator <>)

(defmulti attribute->sql identity)

(defmethod attribute->sql :default [attr]
  (syntax-error "Invalid attribute" attr))

(defmacro defattribute [name column]
  `(do (defmethod attribute->sql '~name [_#]
         ~column)
       (defmethod ->sql '~name [_#]
         [~column []])))

;; (defattribute date "d.date")
(defattribute title "d.title")

(defmethod ->sql '(tag) [[_ & tags]]
  [(format "array_agg(t.name) @> array[%s]::text[]"
           (s/join ", " (repeat (count tags) "?")))
   tags])

(defmethod ->sql '(untagged) [[]]
  ["array_agg(t.name) = array[NULL]" []])

(defmethod ->sql '(like) [[_ attr pattern]]
  [(str (attribute->sql attr) " ILIKE ?" ) [pattern]])

(defmethod ->sql '(content) [[_ & words]]
  ["d.id IN (SELECT document FROM document_pages WHERE page IN (SELECT id from pages_fulltext(?)))"
   [(str (s/join " | " (map #(str \' % \') words)))]])

;;; ANY operator: Searches every text-field for (partial) matches
(defmethod ->sql '(any) [[_ & words]]
  ;; Currently handles title, content, and tags
  (->sql (cons 'or
               (concat
                (map #(list 'like 'title (str "%" % "%")) words)
                [(cons 'content words)
                 (cons 'tag words)]))))

(defn sql-value? [expr]
  (or (isa? (class expr) java.util.Date)
      (string? expr)))

(defmethod ->sql :default [expr]
  (cond (sql-value? expr) ["?" [expr]]
        :else (syntax-error "Unknown expression" expr)))

(comment
  (->sql '(and (tag "foo") (tag "bar")))
  (->sql '(tag "foo" "bar"))
  ;; TODO: created/modified/document-date
  (->sql '(and (> #inst "2014-12-10T15:46:14.635-00:00" date #inst "2014-12-10T15:46:14.635-00:00")
               (tag "foo")))
  ;; TODO: NOT
  (->sql '(not (tag "foo"))))
