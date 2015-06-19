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
  ColumnDropTarget
  (accepts-drop? [_ state]
    true)
  ;; TODO: Make immutable?
  (accept-drop! [_ state pages]
    (println "dropping" (pr-str pages))
    (om/transact! state ::fake-column-pages #(vec (concat % pages)))))

;;; NOTE: We need on-drag-start in `inbox-column' and in
;;; `inbox-column-page' (the latter to handle selection-updates when
;;; dragging). We bubble it from `inbox-column-page', adding its own
;;; `:id' and use that id to update the selection information.
(ui/defcomponent inbox-column-page [page owner {:keys [page-click!]}]
  (render [_]
    [:li.page {:draggable true          ;TODO: only `:draggable' when
                                        ;`(not :unsaved?)'
               :class [(when (:selected? page) "selected")
                       ;; TODO: `:unsaved?'
                       (when (:working? page)  "working")]
               :on-drag-start (fn [e]
                                (println "inbox-column-page")
                                (doto e.dataTransfer
                                  (.setData "application/x-pepa-page" (:id page))))
               :on-click (fn [e]
                           (page-click!
                            (selection/event->click (:id page) e))
                           (ui/cancel-event e))}
     (om/build page/thumbnail page
               {:opts {:enable-rotate? true}})]))

(defn ^:private column-drag-start
  "Called from `inbox-column' when `dragstart' event is fired. Manages
  selection-updates and sets the drag-data in the event."
  [state column owner e]
  (println "on-drag-start")
  (let [selection (om/get-state owner :selection)
        ;; Update selection with the current event object
        dragged-page (some-> e.dataTransfer
                             (.getData "application/x-pepa-page")
                             (read-string))
        ;; If the dragged-page is already in selection, don't do
        ;; anything. Else, update selection as if we clicked on the
        ;; page.
        selection (if (contains? (:selected selection) dragged-page)
                    selection
                    (selection/click selection (selection/event->click dragged-page e)))
        ;; this approach makes sure the pages arrive in the correct order
        page-ids (filterv (:selected selection)
                          (map :id (column-pages column state)))]
    ;; Update `:selection' in `owner'
    (om/set-state! owner :selection selection)
    (doto e.dataTransfer
      (.setData "application/x-pepa-pages" (pr-str page-ids))
      (.setData "text/plain" (pr-str page-ids)))))

(defn ^:private mark-page-selected
  "Assocs {:selected? true} to `page' if `selected-pages'
  contains (:id page). Used in `index-column'."
  [selected-pages page]
  (assoc page :selected? (contains? (set selected-pages) (:id page))))

(ui/defcomponent inbox-column [[state column] owner opts]
  (init-state [_]
    {:selection (->> (column-pages column state)
                     (map :id)
                     (selection/make-selection))})
  (will-receive-props [_ new-state]
    ;; Handle changed contents of this components column by resetting
    ;; the selection
    (let [[old-state old-column] (om/get-props owner)
          [new-state new-column] new-state
          old-pages (mapv :id (column-pages old-column old-state))
          new-pages (mapv :id (column-pages new-column new-state))]
      (when (not= (om/value old-pages)
                  (om/value new-pages))
        (om/set-state! owner :selection
                       (selection/make-selection new-pages)))))
  ;; ;; Rotate all pages randomly
  ;; (will-mount [_]
  ;;   (go-loop []
  ;;     (<! (async/timeout 100))
  ;;     (om/transact! state [:inbox :pages] (fn [pages]
  ;;                                           (update-in pages [(rand-int (count pages)) :rotation]
  ;;                                                      #(mod (+ % 90) 360))))
  ;;     (recur)))
  (render-state [_ {:keys [selection]}]
    ;; NOTE: `column' needs to be a value OR we need to extend cursors
    [:.column {:on-drag-over (when (satisfies? ColumnDropTarget (om/value column))
                               (fn [e]
                                 (when (accepts-drop? column state)
                                   (.preventDefault e))))
               :on-drag-start (partial column-drag-start state column owner)
               :on-drag-end (fn [_]
                              (println "on-drag-end"))
               :on-drop (fn [e]
                          (let [page-cache (::page-cache column)]
                            (when-let [page-ids (some-> e.dataTransfer
                                                        (.getData "application/x-pepa-pages")
                                                        (read-string))]
                              (accept-drop! column state (mapv page-cache page-ids))
                              (ui/cancel-event e))))}
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
    ;; We generate a lookup table from all known pages so `on-drop' in
    ;; `inbox-column' can access it (as the drop `dataTransfer' only
    ;; contains IDs)
    (let [page-cache (into {}
                           ;; Transducerpower
                           (comp (mapcat #(column-pages % state))
                                 (map (juxt :id identity)))
                           columns)]
      [:.workflow.inbox
       (om/build-all inbox-column columns
                     {:fn (fn [column]
                            [state
                             (assoc column ::page-cache page-cache)])})])))
