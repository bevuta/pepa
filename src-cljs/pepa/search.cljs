(ns pepa.search
  (:require [pepa.search.parser :as parser]
            [pepa.api :as api]
            [clojure.string :as s]
            [om.core :as om]

            [cljs.core.match]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.match.macros :refer [match]]
                   [cljs.core.async.macros :refer [go go-loop]]))

(defrecord Search [query result-chan results])

(defn parse-query-string
  "Tries to parse S as a search query. Returns nil in case of
  failure."
  [s]
  (when-not (s/blank? s)
    (parser/parse-string s)))

(defn route->query
  "Extract a search query from the current navigation route. Returns
  nil if not possible."
  [route]
  {:pre [(vector? route)]}
  (match [route]
    [[:tag tag]]     (list 'tag tag)
    [[:query query]] query
    :else nil))

(defn query-string
  "If current :route is a search query, return it as a string, nil
  otherwise."
  [state]
  (match [(-> state om/value :navigation :route)]
    [[:search [:tag tag]]] (str "tag:" (pr-str tag))
    [[:search [:query query]]] query
    :else nil))

(defn search-active? [search]
  ;; {:pre [(= Search (type search))]}
  (:result-chan search))

(defn ^:private cancel-search! [search]
  {:pre [(om/transactable? search)]}
  (when-let [ch (::result-chan search)]
    (async/close! ch)
    (println "Canceling previous search:" search)
    (om/transact! search dissoc ::result-chan)))

(defn search-results [search]
  (:result-chan search))

;;; TODO: We might want to introduce a search-result-cache.

(defn start-search! [query]
  (let [query (cond
                (= query ::all)
                query
                
                (list? query)
                query

                (string? query)
                (parse-query-string query))
        ch (if (= query ::all)
             ;; TODO: Close chan
             (api/fetch-document-ids)
             (api/search-documents query))]
    (println "Running search:" query)
    (map->Search {:query query, :result-chan ch})))

(defn all-documents? [search]
  (= ::all (:query search)))

(defn all-documents! []
  (start-search! ::all))
