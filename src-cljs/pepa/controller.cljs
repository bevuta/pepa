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

(defrecord Controller [!state
                       search-results
                       navigation-events])

(defn new-controller []
  (map->Controller {:!state (atom (model/new-state))
                    :search-results nil
                    :navigation-events nil}))

;;; Utils

;;; TODO: Move to dedicate NS
(def log (partial println "controller:"))

;;; Data Fetching

(s/def ::document-ids (s/coll-of integer? :into #{}))

(defn required-documents [state]
  {:post [(s/valid? (s/nilable ::document-ids) %)]}
  (let [{::route/keys [handler route-params]} state]
    (match [handler]
      [:dashboard]
      (set (get-in state [:search :results]))

      [:search]
      (set (get-in state [:search :results]))

      ;; [:inbox]
      ;; #{}

      [:document]
      #{(:id route-params)}

      [:document-page]
      #{(:id route-params)}

      :else
      (do (js/console.warn "Unimplemented `required-documents" (pr-str handler))
          #{}))))

(defn needs-inbox? [state]
  (= :inbox (::handler state)))

(defn check-search [state]
  (when (= :search (::route/handler state))
    (let [current-search (:search state)
          new-query (some-> state
                            (get-in [::route/route-params :query])
                            (search/parse-query-string))]
      (when (not= (:query current-search) new-query)
        (println "Got a new query: " (pr-str new-query))
        (some-> current-search search/cancel!)
        (search/start-search! new-query)))))

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
          new-search (check-search state)]
      (when new-search
        ;; Pipe search results into controller.search-results
        (-> (async/map #(vector :search-results %) [(:result-chan new-search)])
            (async/pipe (:search-results controller) false)))
      (go
        (let [documents      (some-> documents      <!)
              tags           (some-> tags           <!)
              inbox          (some-> inbox          <!)]
          (prn {:documents documents
                :tags tags
                :inbox inbox
                :new-search new-search})
          (swap! !state (fn [state]
                          (cond-> state
                            (seq documents)
                            (model/store-documents documents)

                            (seq tags)
                            (model/store-tags tags)

                            (seq inbox)
                            ;; TODO: Move to inbox
                            (assoc-in [:inbox :pages] inbox)
                            
                            new-search
                            (assoc :search new-search)))))))))

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
  (let [navigation-events (start-navigation!)
        search-results (async/chan (async/dropping-buffer 1))
        controller (assoc controller
                          :navigation-events navigation-events
                          :search-results search-results)]
    (go-loop []
      (try
        (let [[value ch] (alts! [navigation-events
                                 search-results])]
          (when (and value ch)
            (log "Got event:" (pr-str value))
            (match [value ch]
              [route navigation-events]
              (do
                ;; TODO: This might belong to somewhere else
                (swap! (:!state controller) into route)
                (<! (#'route-changed! controller)))
              ;; Hnadle search results
              [[:search-results result-set] _]
              (do
                (println "Got search results: " (pr-str result-set))
                (swap! (:!state controller) #(assoc-in % [:search :results] result-set))
                (js/console.warn "Unimplemented: Trigger document-download")))
            (recur)))
        (finally
          (println "controller/loop: exiting"))))
    controller))
