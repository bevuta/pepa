(ns pepa.search
  (:require [pepa.search.parser :as parser]
            [pepa.api :as api]
            [pepa.model.route :as route]
            
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

;;; TODO: Belongs to backend.model (or so)
(defn query-string
  "If current :route is a search query, return it as a string, nil
  otherwise."
  [state]
  (let [{::route/keys [handler route-params]} state]
    (prn handler route-params)
    (match [handler route-params]
      [:search {:tag tag}]     (str "tag:" (pr-str tag))
      [:search {:query query}] query
      :else nil)))

;;; TODO: Remove
(defn search-active? [search]
  ;; {:pre [(= Search (type search))]}
  (:result-chan search))

(defn cancel! [search]
  {:pre [(:result-chan search)]}
  (async/close! (:result-chan search)))

;;; TODO: We might want to introduce a search-result-cache.
;;; TODO: Rename `start-search!` and create a separate function for requiring all documents
(defn start-search! [query]
  (let [query (cond
                (= query ::all)
                query
                
                (list? query)
                query

                (string? query)
                (parse-query-string query))
        ch (if (= query ::all)
             (api/fetch-document-ids)
             (api/search-documents query))]
    (println "Running search:" query)
    (map->Search {:query query, :result-chan ch})))

(defn all-documents? [search]
  (= ::all (:query search)))

(defn all-documents! []
  (start-search! ::all))
