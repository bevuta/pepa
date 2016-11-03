(ns pepa.controller
  (:require [pepa.api :as api]
            [pepa.model :as model]
            [pepa.search :as search]
            [pepa.navigation :as nav]

            [cljs.core.async :as async :refer [<! >!]]
            [cljs.core.match]

            [goog.events :as events])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [cljs.core.match.macros :refer [match]])
  (:import goog.History
           goog.history.EventType))

(defrecord Controller [!state history navigation-events])

(defn new-controller []
  (->Controller (atom (model/new-state))
                nil
                nil))

(defn fetch-initial-data! [{:keys [!state]}]
  (println "controller: Fetching initial data")
  ;; Fetch Tags
  (async/take! (api/fetch-tags true)
               (fn [tags]
                 (swap! !state model/store-tags tags)))

  ;; Run search to get document-ids
  ;; TODO: Handle `route`
  (go
    (let [search (search/all-documents!)
          ids (<! (search/search-results search))
          documents (<! (api/fetch-documents ids))]
      (swap! !state (fn [state]
                      (-> state
                          (assoc-in [:search :results] ids)
                          (update :documents #(into % (map (juxt :id identity)) documents))))))))

(defn handle-transition! [controller new-route]
  (println "controller: Transitioning to new route:" new-route)
  (js/console.error "unimplemented"))

(defn start! [controller]
  (println "controller: Starting")
  (fetch-initial-data! controller)
  (let [navigation-events (async/chan (async/dropping-buffer 1))
        history (doto (History.)
                  (goog.events/listen EventType.NAVIGATE
                                      (fn [event]
                                        (let [token (str "/#" (.-token event))]
                                          (println "Got navigation event:" token)
                                          (async/put! navigation-events token))))
                  (.setEnabled true))]
    (go-loop []
      (when-let [route (<! navigation-events)]
        (let [route (nav/parse-route route)]
          (swap! (:!state controller) assoc :navigation route))
        (recur))
      (println "controller/navigation-loop: exiting"))
    (assoc controller
           :history history
           :navigation-events navigation-events)))
