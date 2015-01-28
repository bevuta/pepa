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

(defn search-query [state]
  (match [(-> (om/value state) :navigation :route)]
    [[:search [:query q]]] q
    [[:search [:tag tag]]] (str "tag:" (pr-str tag))
    :else nil))

(defn search-results [state]
  (:search/results state))

(defn search-active? [state]
  (-> state :search/channel boolean))

(defn ^:private cancel-search! [state]
  (when-let [ch (:search/channel state)]
    (async/close! ch)
    (println "Canceled previous search"
             (str "(for " (pr-str (:search/query state)) ")"))
    (om/update! state :search/channel nil)))

(defn clear-results!
  "Resets search results."
  [state]
  (cancel-search! state)
  (om/transact! state (fn [state]
                        (assoc state
                               :search/query nil
                               :search/results nil
                               :search/channel nil))))

(defn all-documents!
  "Fetches all document-ids and stores them in :search/results."
  [state & [force?]]
  (go
    (when (or (not= ::all (:search/query state))
              force?)
      (cancel-search! state)
      (let [ch (api/fetch-document-ids)]
        (om/transact! state #(assoc %
                                    :search/query ::all
                                    :search/channel ch))
        (when-let [results (<! ch)]
          (om/transact! state #(assoc %
                                      :search/results results
                                      :search/channel nil)))))))

(defn search! [state query]
  (go
    (let [query (try
                  (cond 
                    (list? query)
                    query
                    (string? query)
                    (parse-query query))
                  (catch js/Error e nil))]
      (when (not= query (:search/query state))
        (println "got new query")
        ;; Cancel old search & clear results
        (clear-results! state)
        ;; Start new search, store result channel in app-state
        (let [chan (api/search-documents query)]
          (om/transact! state (fn [state]
                                (assoc state
                                       :search/query query
                                       :search/channel chan)))
          (when-let [results (<! chan)]
            (doto state
              (om/update! :search/channel nil)
              (om/update! :search/results results))))))))
