(ns pepa.search
  (:require [pepa.search.parser :as parser]
            [pepa.api :as api]
            [clojure.string :as s]
            [om.core :as om]

            [cljs.core.match]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.match.macros :refer [match]]
                   [cljs.core.async.macros :refer [go go-loop]]))

(defn parse-query [s]
  (when-not (s/blank? s)
    (parser/parse-string s)))

(defn search-query
  "If current :route is a search query, return the query as data
  structure. Nil otherwise."
  [state]
  (match [(-> state om/value :navigation :route)]
    [[:search [:tag tag]]]     (list 'tag tag)
    [[:search [:query query]]] query
    :else nil))

(defn query-string
  "If current :route is a search query, return it as a string, nil
  otherwise."
  [state]
  (match [(-> state om/value :navigation :route)]
    [[:search [:tag tag]]] (str "tag:" (pr-str tag))
    [[:search [:query query]]] query
    :else nil))

(defn search-results [state]
  (:search/results state))

(defn search-active? [state]
  (-> state :search/channel boolean))

(defn ^:private cancel-search! [state]
  (when-let [ch (:search/channel state)]
    (async/close! ch)
    (println "Canceling previous search")
    (om/update! state :search/channel nil)))

(defn clear-results!
  "Resets search results."
  [state]
  (cancel-search! state)
  (om/transact! state (fn [state]
                        (assoc state
                               :search/results nil
                               :search/channel nil))))

(defn all-documents!
  "Fetches all document-ids and stores them in :search/results."
  [state]
  (go
    (cancel-search! state)
    (let [ch (api/fetch-document-ids)]
      (om/update! state :search/channel ch)
      (when-let [results (<! ch)]
        (om/transact! state #(assoc %
                                    :search/results results
                                    :search/channel nil))))))

(defn search! [state query]
  (go
    (let [query (try
                  (cond 
                    (list? query)
                    query
                    (string? query)
                    (parse-query query))
                  (catch js/Error e nil))]
      (println "Running search:" query)
      ;; Cancel old search
      (cancel-search! state)
      ;; Start new search, store result channel in app-state
      (let [chan (api/search-documents query)]
        (om/update! state :search/channel chan)
        (when-let [results (<! chan)]
          (clear-results! state)
          (doto state
            (om/update! :search/channel nil)
            (om/update! :search/results results)))))))
