(ns pepa.workflows.inbox2
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom]

            [clojure.string :as s]
            [cljs.core.async :as async :refer [<!]]
            [cljs.core.match]

            [nom.ui :as ui]
            [pepa.api :as api]
            [pepa.data :as data]
            [pepa.selection :as selection]

            [pepa.components.page :as page]

            [cljs.reader :refer [read-string]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]])
  (:import [cljs.core.ExceptionInfo]))

(defprotocol ColumnSource
  (column-title [_])
  (column-pages  [_ state]))

(defprotocol ColumnDropTarget
  (accepts-drop? [_ state #_pages])     ;can't get data in drag-over
  (accept-drop!  [_ state pages]))

(comment
  (defprotocol PageUpdateHandler
    (-update-pages [_ state pages]
      "Function called to notify a column of page updates. `pages' is a
    list of updated pages. There is no guarantee that `pages' only
    contains pages relevant for the implementor. Implementations
    should do the necessary updates on themselves and return the
    updated versions."))

  (defn update-pages!
  "Notify columns in Inbox that pages in `pages' were updated on the server."
  [state pages]
  (om/transact! state [:inbox :columns]
                (fn [columns]
                  (->> columns
                       (map #(-update-pages % state pages))
                       (into (empty columns)))))))

;;; TODO: `remove-pages!'

(defrecord InboxColumnSource []
  ColumnSource
  (column-title [_]
    "Inbox")
  (column-pages [_ state]
    (get-in state [:inbox :pages])))

(defrecord FakeColumnSource []
  ColumnSource
  (column-title [_]
    "Fake")
  (column-pages [_ state]
    (get-in state [::fake-column-pages]))
  ;; ColumnDropTarget
  ;; (accepts-drop? [_ state]
  ;;   true)
  ;; ;; TODO: Make immutable?
  ;; (accept-drop! [_ state pages]
  ;;   (println "dropping" (pr-str pages))
  ;;   (om/transact! state ::fake-column-pages #(vec (concat % pages))))
  )

(ui/defcomponent inbox-column-page [page owner {:keys [page-click!]}]
  (render [_]
    [:li.page {;; :draggable true
               :class [(when (:selected? page) "selected")]
               :on-click (fn [e]
                           (page-click!
                            (selection/event->click (:id page) e))
                           (ui/cancel-event e))}
     (om/build page/thumbnail page
               {:opts {:enable-rotate? true}})]))

(defn ^:private mark-page-selected [selected-pages page]
  (assoc page :selected? (contains? (set selected-pages) (:id page))))

(ui/defcomponent inbox-column [[state column] owner opts]
  (init-state [_]
    {:selection (->> (column-pages column state)
                     (map :id)
                     (selection/make-selection))})
  ;; TODO: Reset selection elements 
  (render-state [_ {:keys [selection]}]
    ;; NOTE: `column' needs to be a value OR we need to extend cursors
    [:.column { ;; :on-drag-over (when (satisfies? ColumnDropTarget (om/value column))
               ;;                 (fn [e]
               ;;                   (when (accepts-drop? column state)
               ;;                     (.preventDefault e))))
               
               ;; :on-drag-start (fn [e]
               ;;                  (println "on-drag-start")
               ;;                  (let [drag-pages (keep selected-pages (map :id (column-pages column state)))]
               ;;                    (doto e.dataTransfer
               ;;                      (.setData "application/x-pepa-pages" (pr-str drag-pages))
               ;;                      (.setData "text/plain" (pr-str (vec drag-pages))))))
               ;; :on-drag-end (fn [_]
               ;;                (println "on-drag-end"))
               
               ;; :on-drop (fn [e]
               ;;            (when-let [pages (some-> e.dataTransfer
               ;;                                     (.getData "application/x-pepa-pages")
               ;;                                     (read-string))]
               ;;              (accept-drop! column state pages)
               ;;              (ui/cancel-event e)))
               }
     [:header (column-title column)]
     [:ul.pages
      (om/build-all inbox-column-page (column-pages column state)
                    {:opts {:page-click! (fn [click] 
                                           (om/update-state! owner :selection
                                                             #(selection/click % click)))}
                     :fn (partial mark-page-selected (:selected selection))})]]))

(ui/defcomponent inbox [state owner opts]
  (init-state [_]
    {:columns [(->InboxColumnSource)
               (->FakeColumnSource)]})
  (will-mount [_]
    (api/fetch-inbox! state))
  (render-state [_ {:keys [columns selected-pages]}]
    [:.workflow.inbox
     (om/build-all inbox-column columns
                   {:fn (fn [column]
                          [state column])})]))
