(ns pepa.controller
  (:require [pepa.api :as api]
            [pepa.model :as model]
            [pepa.model.route :as route]

            [pepa.search :as search]
            [pepa.navigation :as nav]

            [clojure.spec :as s]

            [cljs.core.async :as async :refer [alts! <! >!]]
            [cljs.core.match]

            [goog.events :as events])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [cljs.core.match.macros :refer [match]])
  (:import goog.History
           goog.history.EventType))

(defrecord Controller [!state navigation-events])

(defn new-controller []
  (->Controller (atom (model/new-state))
                nil))

;;; Utils

;;; TODO: Move to dedicate NS
(def log (partial println "controller:"))

;;; Data Fetching

(s/def ::document-ids (s/coll-of integer? :into #{}))

(defn required-documents [state]
  {:post [(s/valid? (s/nilable ::document-ids) %)]}
  (let [{::route/keys [handler route-params]} state]
    (match [handler]
      [:dashboard]     (set (get-in state [:search :results]))
      ;; TODO: `pepa.inbox2` needs a function to tell us which documents it needs
      [:inbox]         #{}
      [:document]      #{(:id route-params)}
      [:document-page] #{(:id route-params)}
      :else (js/console.warn "Unimplemented `necessary-data`:" (pr-str handler)))))

(defn needs-inbox? [state]
  (= :inbox (::handler state)))

;;; Event Handling

(defn route-changed! [controller]
  ;; TODO: Simply return a set of side effects to perform
  (let [!state (:!state controller)
        state @!state
        required-documents (required-documents state)
        inbox-required? (needs-inbox? state)
        tags-required? (empty? (:tags state))]
    (log "transitioning to new route:" ((juxt ::route/handler ::route/query-params) state))
    (let [documents (some->> (seq required-documents)
                             (set)
                             (remove (set (keys (:documents state))))
                             (api/fetch-documents))
          tags (when tags-required?
                 (api/fetch-tags true))
          inbox (when inbox-required?
                  (api/fetch-inbox))
          search-results (-> state :search :result-chan)]
      (go
        (let [documents      (some-> documents      <!)
              tags           (some-> tags           <!)
              inbox          (some-> inbox          <!)
              search-results (some-> search-results <!)]
          (prn documents tags inbox search-results)
          (swap! !state (fn [state]
                          (cond-> state
                            (seq documents)
                            (update :documents merge documents)

                            (seq tags)
                            (model/store-tags tags)

                            (seq inbox)
                            (assoc-in [:inbox :pages] inbox)

                            (seq search-results)
                            (assoc-in [:search :results] search-results)))))))
    ;; TODO Handle pending search
    ;; ()
    ))

(defn start-navigation! []
  (let [navigation-events (async/chan (async/dropping-buffer 1))
        history (doto (History.)
                  (goog.events/listen EventType.NAVIGATE
                                      (fn [event]
                                        (let [token (str "/#" (.-token event))]
                                          (println "Got navigation event:" token)
                                          (async/put! navigation-events token))))
                  (.setEnabled true))]
    ;; Put current route into ch
    ;;(async/put! navigation-events js/window.location.hash)
    (async/map nav/parse-route [navigation-events])))

(defn start! [controller]
  (log "Starting")
  (let [navigation-events (start-navigation!)]
    (go-loop []
      (let [[value ch] (alts! [navigation-events])]
        (when (and value ch)
          (log "Got event:" (pr-str value))
          (match [value ch]
            [route navigation-events]
            (do
              ;; TODO
              (swap! (:!state controller) into route)
              (<! (#'route-changed! controller))))
          (recur)))
      (println "controller/loop: exiting"))
    (assoc controller
           :navigation-events navigation-events)))
