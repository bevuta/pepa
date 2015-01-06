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

(defn clear-results!
  "Resets search results to *all* documents on the server."
  [state]
  (go
    (om/update! state :search/results
                (<! (api/fetch-document-ids)))))

(defn search! [state query]
  (go
    (some->> (try
               (cond 
                 (list? query)
                 query
                 string?
                 (parse-query query))
               (catch js/Error e nil))
             (api/search-documents)
             (<!)
             (om/update! state :search/results))))
