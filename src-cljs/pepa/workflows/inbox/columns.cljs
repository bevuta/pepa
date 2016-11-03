(ns pepa.workflows.inbox.columns
  (:require cljs.core.match
            [clojure.string :as s]
            [goog.string :as gstring]
            [pepa.navigation :as nav])
  (:require-macros [cljs.core.match.macros :refer [match]]))

(defprotocol ColumnSpec
  (column-spec [col]))

(defn- parse-column-param [param]
  {:post [(every? vector? %)]}
  (let [entries (s/split param #",")
        pairs (mapv #(s/split % #":") entries)]
    (for [[k v] pairs]
      (if-let [key (case k
                     "d" :document
                     "f" :file
                     "i" :inbox
                     "n" :new-document
                     "s" :search
                     nil)]
        (let [value (if (= :search key)
                      (if v (gstring/urlDecode v) "")
                      (try (let [n (js/parseInt v 10)]
                             (when (integer? n) n))
                           (catch js/Error e nil)))]
          [key value])
        (js/console.error "Invalid column-spec: " (str k))))))

(defn- serialize-column-param [column]
  {:pre [(vector? column)
         (keyword? (first column))]}
  (match [column]
    [[:document id]] (str "d:" id)
    [[:inbox _]] "i"
    [[:new-document _]] "n"
    [[:search s]] (str "s:" (gstring/urlEncode (or s "")))))

(defn- serialize-column-params [columns]
  (s/join "," (map serialize-column-param columns)))

(defn current-columns [props]
  (->> (or (get-in props [:navigation :query-params :columns])
           "i,n")
       (parse-column-param)
       (filterv identity)))

(defn remove-column [columns column]
  {:pre [(vector? column)]}
  (println "removing column" (pr-str column))
  (->> columns
       (remove #(= column %))
       (vec)))

(defn replace-column [columns old new]
  {:pre [(vector? old) (vector? new)]}
  (->> columns
       (mapv #(if (= old %) new %))))

(defn add-column [columns column]
  {:pre [(vector? column)
         (keyword? (first column))
         (= 2 (count column))]}
  (let [;; Special case: If last column is 'new
        ;; document', insert before it
        last-column (last columns)
        to-add (if (= [:new-document nil] last-column)
                 [column last-column]
                 [last-column column])
        columns (butlast columns)]
    (-> (vec columns)
        (into to-add))))

(defn show-columns! [columns]
  {:pre [(every? vector? columns)]}
  (prn "navigating to" columns)
  (let [columns (serialize-column-params columns)]
    (nav/navigate! (nav/inbox-route {:columns columns}))))

(defn add-search-column [columns]
  (if-not (some (fn [[k _]] (= :search k)) columns)
    (add-column columns [:search nil])
    columns))

(defn inbox-column->column [col]
  {:pre [(satisfies? ColumnSpec col)]}
  (column-spec col))
