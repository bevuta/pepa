(ns pepa.controller
  (:require [pepa.api :as api]
            [pepa.model :as model]
            [pepa.search :as search]

            [cljs.core.async :as async :refer [<! >!]]
            [cljs.core.match])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [cljs.core.match.macros :refer [match]]))

(defrecord Controller [state])

(defn new-controller []
  (->Controller (atom (model/new-state))))

(defn fetch-initial-data! [{:keys [state]}]
  (println "controller: Fetching initial data")
  ;; Fetch Tags
  (async/take! (api/fetch-tags true)
               (fn [tags]
                 (swap! state model/store-tags tags)))

  ;; Run search to get document-ids
  ;; TODO: Handle `route`
  (go
    (let [search (search/all-documents!)
          ids (<! (search/search-results search))
          documents (<! (api/fetch-documents ids))]
      (swap! state (fn [state]
                     (-> state
                         (assoc-in [:search :results] ids)
                         (update :documents #(into % (map (juxt :id identity)) documents))))))))

(defn handle-transition! [controller new-route]
  (println "controller: Transitioning to new route:" new-route)
  (js/console.error "unimplemented"))
