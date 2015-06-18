(ns pepa.workflows.inbox2
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom]

            [clojure.string :as s]
            [cljs.core.async :as async :refer [<!]]
            [cljs.core.match]

            [nom.ui :as ui]
            [pepa.api :as api]
            [pepa.data :as data]

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
  ColumnDropTarget
  (accepts-drop? [_ state]
    true)
  (accept-drop! [_ state pages]
    (println "dropping" (pr-str pages))
    (om/transact! state ::fake-column-pages #(vec (concat % pages)))))

(ui/defcomponent inbox-column-page [page]
  (render [_]
    [:li.page {:on-drag-start (fn [e]
                                (println "on-drag-start")
                                (doto e.dataTransfer
                                  (.setData "application/x-pepa-pages" (pr-str [page]))
                                  (.setData "text/plain" (pr-str [(:id page)]))))
               :draggable true
               :on-drag-end (fn [_]
                              (println "on-drag-end"))}
     (om/build page/thumbnail page
               {:opts {:enable-rotate? true}})]))

(ui/defcomponent inbox-column [[state column]]
  (render [_]
    ;; NOTE: `column' needs to be a value OR we need to extend cursors
    [:.column {:on-drag-over (when (satisfies? ColumnDropTarget (om/value column))
                               (fn [e]
                                 (when (accepts-drop? column state)
                                   (.preventDefault e))))
               :on-drop (fn [e]
                          (when-let [pages (some-> e.dataTransfer
                                                   (.getData "application/x-pepa-pages")
                                                   (read-string))]
                            (accept-drop! column state pages)
                            (ui/cancel-event e)))}
     [:header (column-title column)]
     [:ul.pages
      (om/build-all inbox-column-page (column-pages column state))]]))

(ui/defcomponent inbox [state owner opts]
  (init-state [_]
    {:columns [(->InboxColumnSource)
               (->FakeColumnSource)]})
  (will-mount [_]
    (api/fetch-inbox! state))
  (render-state [_ {:keys [columns]}]
    [:.workflow.inbox
     (om/build-all inbox-column columns
                   {:fn (fn [column]
                          [state column])})]))
