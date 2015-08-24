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
  (:import [cljs.core.ExceptionInfo]
           goog.ui.IdGenerator))

(defprotocol ColumnSource
  (column-title [_ state])
  (column-pages  [_ state])
  ;; TODO: Handle immutable column sources
  ;; TODO: How do we handle removal of the last page for documents?
  (remove-pages! [_ state page-ids]))

(defprotocol ColumnDropTarget
  (accepts-drop? [_ state])     ;can't get data in drag-over
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

(defrecord InboxColumnSource [id]
  ColumnSource
  (column-title [_ _]
    "Inbox")
  (column-pages [_ state]
    (get-in state [:inbox :pages]))
  (remove-pages! [_ state page-ids]
    (go
      (println "Removing pages from Inbox...")
      ;; TODO: Handle updates coming via the poll channel
      (<! (api/delete-from-inbox! page-ids))
      ;; TODO: Is this necessary?
      (let [page-ids (set page-ids)]
        (om/transact! state [:inbox :pages]
                      (fn [pages]
                        (into (empty pages)
                              (remove #(contains? page-ids (:id %)))
                              pages))))))
  ColumnDropTarget
  (accepts-drop? [_ state]
    true)
  ;; TODO: Make immutable?
  (accept-drop! [_ state new-pages]
    (go
      (println "dropping" (pr-str new-pages) "on inbox")
      (<! (api/add-to-inbox! (map :id new-pages)))))
  om/IWillMount
  (will-mount [_]
    (api/fetch-inbox!)))

(defrecord DocumentColumnSource [id document-id]
  ColumnSource
  (column-title [_ state]
    (get-in state [:documents document-id :title]
            "Untitled Document"))
  (column-pages [_ state]
    (get-in state [:documents document-id :pages]))
  (remove-pages! [_ state page-ids]
    (go
      (if-let [document (om/value (get-in state [:documents document-id]))]
        (<! (api/update-document! (update document
                                          :pages
                                          #(remove (comp (set page-ids) :id) %))))
        (js/console.error (str "[DocumentColumnSource] Failed to get document " document-id)
                          {:document-id document-id}))))
  ColumnDropTarget
  (accepts-drop? [_ state]
    true)
  (accept-drop! [_ state new-pages]
    (go
      (println "dropping" (pr-str new-pages) "on document" document-id)
      (let [document (om/value (get-in state [:documents document-id]))]
        (<! (api/update-document! (update document :pages
                                          #(into % new-pages))))
        (println "Saved!"))))
  om/IWillMount
  (will-mount [_]
    (assert (number? document-id))
    (api/fetch-documents! [document-id])))

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

(defn ^:private get-transfer-data [e key]
  (some-> e.dataTransfer
          (.getData (name key))
          (read-string)))

(defn ^:private column-drag-start
  "Called from `inbox-column' when `dragstart' event is fired. Manages
  selection-updates and sets the drag-data in the event."
  [state column owner e]
  (println "on-drag-start")
  (let [selection (om/get-state owner :selection)
        ;; Update selection with the current event object
        dragged-page (get-transfer-data e "application/x-pepa-page")
        ;; If the dragged-page is already in selection, don't do
        ;; anything. Else, update selection as if we clicked on the
        ;; page.
        selection (if (contains? (:selected selection) dragged-page)
                    selection
                    (selection/click selection (selection/event->click dragged-page e)))
        ;; this approach makes sure the pages arrive in the correct order
        page-ids (keep (comp (:selected selection) :id)
                       (column-pages column state))]
    ;; Update `:selection' in `owner'
    (om/set-state! owner :selection selection)
    (doto e.dataTransfer
      (.setData "application/x-pepa-pages" (pr-str page-ids))
      (.setData "application/x-pepa-column" (pr-str (:id column)))
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
  (render-state [_ {:keys [selection handle-drop!]}]
    ;; NOTE: `column' needs to be a value OR we need to extend cursors
    [:.column {:on-drag-over (when (satisfies? ColumnDropTarget (om/value column))
                               (fn [e]
                                 (when (accepts-drop? column state)
                                   (.preventDefault e))))
               :on-drag-start (partial column-drag-start state column owner)
               :on-drag-end (fn [_]
                              (println "on-drag-end"))
               :on-drop (fn [e]
                          (let [page-cache (::page-cache column)
                                source-column (get-transfer-data e "application/x-pepa-column")
                                page-ids (get-transfer-data e "application/x-pepa-pages")]
                            ;; Delegate to `inbox'
                            (handle-drop! (:id column)
                                          source-column
                                          page-ids)))}
     [:header (column-title column state)]
     [:ul.pages
      (om/build-all inbox-column-page (column-pages column state)
                    {:opts {:page-click! (fn [click] 
                                           (om/update-state! owner :selection
                                                             #(selection/click % click)))}
                     :fn (partial mark-page-selected (:selected selection))})]]))

(defn ^:private inbox-handle-drop! [state owner page-cache target source page-ids]
  ;; Call `accepts-drop!' in `target'
  (let [columns (om/get-state owner :columns)
        target (get columns target)
        source (get columns source)
        ;; Remove page-ids already in `target'
        target-pages (mapv :id (column-pages target state))
        pages (into []
                    (comp (remove (set target-pages))
                          (map page-cache)
                          (map om/value))
                    page-ids)]
    (if-not (seq pages)
      (js/console.warn "Ignoring drop consisting only of duplicate pages:"
                       (pr-str page-ids))
      (go
        (<! (accept-drop! target state pages))
        (println "Target saved. Removing from source...")
        (<! (remove-pages! source state page-ids))
        (println "Drop saved!")))))

(defn ^:private make-page-cache [state columns]
  (into {}
        ;; Transducerpower
        (comp (mapcat #(column-pages (val %) state))
              #_(map om/value)
              (map (juxt :id identity)))
        columns))

(ui/defcomponent inbox [state owner opts]
  (init-state [_]
    (let [gen (IdGenerator.getInstance)
          columns [(->InboxColumnSource (.getNextUniqueId gen))
                   (->DocumentColumnSource (.getNextUniqueId gen) 1)
                   (->DocumentColumnSource (.getNextUniqueId gen) 2)]]
      {:columns (into {}
                      (map (juxt :id identity))
                      columns)}))
  (will-mount [_]
    ;; HACK: Use om/IWillMount to fetch additional data needed by each
    ;; column
    (doseq [[_ column] (om/get-state owner :columns)]
      (when (satisfies? om/IWillMount column)
        (om/will-mount column))))
  (render-state [_ {:keys [columns]}]
    ;; We generate a lookup table from all known pages so `on-drop' in
    ;; `inbox-column' can access it (as the drop `dataTransfer' only
    ;; contains IDs)
    (let [page-cache (make-page-cache state columns)]
      [:.workflow.inbox
       (om/build-all inbox-column columns
                     {:fn (fn [[_ column]]
                            [state (assoc column ::page-cache page-cache)])
                      :state {:handle-drop! (partial inbox-handle-drop! state owner page-cache)}})])))
