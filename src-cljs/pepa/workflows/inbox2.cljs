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
            [pepa.navigation :as nav]
            [pepa.search :as search]
            [pepa.components.document :as document]
            [pepa.components.tags :as tags]

            [pepa.components.page :as page]

            [cljs.reader :refer [read-string]]
            [goog.string :as gstring]
            goog.events.KeyCodes)
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]])
  (:import [cljs.core.ExceptionInfo]
           goog.ui.IdGenerator))

(defprotocol ColumnSource
  (column-title [_ state])
  (column-header-url [_])
  (column-pages  [_ state])
  ;; TODO: Handle immutable column sources
  ;; TODO: How do we handle removal of the last page for documents?
  (remove-pages! [_ state page-ids]))

(defprotocol SpecialColumn
  (special-column-ui [_]))

(defn special-column? [x]
  (satisfies? SpecialColumn x))

(defprotocol ColumnDropTarget
  (accept-drop! [_ state pages target-idx]))

(defrecord InboxColumnSource [id]
  ColumnSource
  (column-title [_ _]
    "Inbox")
  (column-header-url [_]
    nil)
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
  ;; TODO: Handle TARGET-IDX
  ColumnDropTarget
  (accept-drop! [_ state new-pages target-idx]
    (go
      (let [page-ids (map :id new-pages)]
        (println "dropping" (pr-str page-ids) "on inbox (at index" target-idx ")")
        ;; TODO: Error-Handling
        (<! (api/add-to-inbox! page-ids)))
      ;; Return true, indicating successful drop
      true))
  om/IWillMount
  (will-mount [_]
    (api/fetch-inbox!)))

(declare add-column! replace-column! current-columns)

(defrecord DocumentColumnSource [id document-id]
  ColumnSource
  (column-title [_ state]
    (get-in state [:documents document-id :title]
            "Untitled Document"))
  (column-header-url [_]
    (nav/document-route {:id document-id}))
  (column-pages [_ state]
    (get-in state
            [:documents document-id :pages]
            []))
  (remove-pages! [_ state page-ids]
    (go
      (if-let [document (om/value (get-in state [:documents document-id]))]
        (<! (api/update-document! (update document
                                          :pages
                                          #(remove (comp (set page-ids) :id) %))))
        (js/console.error (str "[DocumentColumnSource] Failed to get document " document-id)
                          {:document-id document-id}))))
  ColumnDropTarget
  (accept-drop! [_ state new-pages target-idx]
    (go
      (println "dropping" (pr-str (map :id new-pages)) "on document" (pr-str document-id)
               (str "(at index " (pr-str target-idx) ")"))
      (let [document (om/value (get-in state [:documents document-id]))]
        (<! (api/update-document! (update document :pages
                                          (fn [pages]
                                            (data/insert-pages pages
                                                               new-pages
                                                               target-idx)))))
        (println "Saved!")
        ;; indicate successful drop
        true)))
  om/IWillMount
  (will-mount [_]
    (assert (number? document-id))
    (api/fetch-documents! [document-id])))

(declare new-document-ui)

(defrecord NewDocumentColumn [id]
  SpecialColumn
  (special-column-ui [_] new-document-ui)
  ColumnDropTarget
  (accept-drop! [_ state new-pages _]
    (go
      (println "Dropping pages on unsaved document...")
      (let [document (<! (api/save-new-document! {:title "New Document"
                                                  :pages new-pages}
                                                 "inbox"))]
        (when document
          (add-column! (current-columns state) [:document (:id document)])
          true)))))

(declare search-ui)

(defrecord SearchColumn [id search]
  SpecialColumn
  (special-column-ui [_] search-ui))

(defn- inbox-page-drag-over [page owner store-idx! e]
  ;; Calculcate the center position for this image and set idx to the
  ;; current page or the page after, respectively
  (let [rect (.getBoundingClientRect (.-currentTarget e))
        top (.-top rect)
        height (.-height rect)
        local (Math/abs (- (.-clientY e) top))
        idx (if (>= (/ height 2) local)
              (:idx page)
              (inc (:idx page)))]
    (store-idx! idx)))

;;; NOTE: We need on-drag-start in `inbox-column' and in
;;; `inbox-column-page' (the latter to handle selection-updates when
;;; dragging). We bubble it from `inbox-column-page', adding its own
;;; `:id' and use that id to update the selection information.
(ui/defcomponent inbox-column-page [page owner {:keys [page-click! store-idx!]}]
  (render [_]
    [:li.page {:draggable true
               :class (let [selected? (:selected? page)]
                        [(when selected? "selected")
                         (when (and (not selected?) (:dragover? page)) "dragover")])
               :on-drag-start (fn [e]
                                (println "inbox-column-page")
                                (.setData e.dataTransfer "application/x-pepa-page" (:id page)))
               :on-drag-over (partial inbox-page-drag-over page owner store-idx!)
               :on-click (fn [e]
                           (page-click!
                            (selection/event->click (:id page) e))
                           (ui/cancel-event e))}
     (om/build page/thumbnail page
               {:opts {:enable-rotate? false}})]))

(defn ^:private get-transfer-data [e key]
  (or (some-> e.dataTransfer
              (.getData (name key))
              (read-string))
      (js/console.warn "Couldn't read transfer-data for key:" (pr-str key))))

(defn- drag-copy? [e]
  e.ctrlKey)

(ui/defcomponent action-bar [[column state selected-pages]]
  (render [_]
    [:.action-bar
     [:.rotate
      [:.left { ;; :on-click (partial rotate-clicked page (- rotation 90))
               :title "Rotate Counterclockwise"
               :key "left"}]
      [:.right { ;; :on-click (partial rotate-clicked page (+ rotation 90))
                :title "Rotate Clockwise"
                :key "right"}]]

     [:.delete {:title "Delete Pages"
                :on-click (fn [e]
                            (ui/cancel-event e)
                            (when (seq selected-pages)
                              (remove-pages! column state selected-pages)))}]]))

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
  [page selected-pages]
  (assoc page :selected? (contains? (set selected-pages) (:id page))))

(ui/defcomponent inbox-column [[state column] owner]
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
  (render-state [_ {:keys [selection handle-drop! drop-idx]}]
    ;; NOTE: `column' needs to be a value OR we need to extend cursors
    [:.column {:on-drag-over (fn [e]
                               (when (satisfies? ColumnDropTarget (om/value column))
                                 (.preventDefault e)))
               :on-drag-start (partial column-drag-start state column owner)
               :on-drag-end (fn [_] (println "on-drag-end"))
               ;; Reset this columns drop-idx to clear the marker
               :on-drag-leave (fn [_] (om/set-state! owner :drop-idx nil))
               :on-drop (fn [e]
                          (println "on-drop")
                          (let [source-column (get-transfer-data e "application/x-pepa-column")
                                page-ids (get-transfer-data e "application/x-pepa-pages")
                                copy? (drag-copy? e)]
                            ;; Delegate to `inbox'
                            (handle-drop! (:id column)
                                          source-column
                                          page-ids
                                          drop-idx
                                          copy?))
                          ;; Remove drop-target
                          (om/set-state! owner :drop-idx nil))}
     [:header
      (column-title column state)
      (when-let [url (column-header-url column)]
        [:a.show {:href url} "Show"])
      ;; [:button.action-bar {:class [(when action-bar? "pressed")]
      ;;                      :on-click (fn [e]
      ;;                                  (ui/cancel-event e)
      ;;                                  (om/update-state! owner :action-bar? not))}]
      ]
     [:ul.pages
      (let [pages (map-indexed (fn [idx page] (assoc page :idx idx))
                               (column-pages column state))]
        (om/build-all inbox-column-page pages
                      {:opts {:page-click! (fn [click]
                                             (om/update-state! owner :selection
                                                               #(selection/click % click)))
                              :store-idx! (fn [idx]
                                            (om/set-state! owner :drop-idx idx))}
                       :fn (fn [page]
                             (-> page
                                 (assoc :dragover? (= drop-idx (:idx page) ))
                                 (mark-page-selected (:selected selection))))}))]
     (when-let [selected-pages (seq (:selected selection))]
       (om/build action-bar [column state selected-pages]))]))

(ui/defcomponent new-document-ui [[state column] owner]
  (render-state [_ {:keys [handle-drop!]}]
    [:.column.new {:on-drag-over (fn [e] (.preventDefault e))
                   :on-drop (fn [e]
                              (println "[new-document-ui] on-drop")
                              (let [source-column (get-transfer-data e "application/x-pepa-column")
                                    page-ids (get-transfer-data e "application/x-pepa-pages")
                                    copy? (drag-copy? e)]
                                ;; Delegate to `inbox'
                                (handle-drop! (:id column)
                                              source-column
                                              page-ids
                                              0
                                              copy?)))}
     [:header nil]
     [:.center
      "Drag pages here or press \"Open\" to open a document"
      [:button {:on-click (fn [e]
                            (ui/cancel-event e)
                            (let [current-columns (current-columns state)]
                              (when-not (some (fn [[k _]] (= :search k)) current-columns)
                                (add-column! current-columns [:search nil]))))}
       "Open"]]]))

(ui/defcomponent search-result-row [document owner {:keys [on-click]}]
  (render [_]
    [:li {:on-click (fn [e] (on-click (om/value document)))}
     (om/build page/thumbnail (first (:pages document)))
     [:.meta
      [:.title (:title document)]
      (when-let [modified (:created document)]
        [:.created (document/format-datetime modified)])
      (om/build tags/tags-list (:tags document))]]))

(defn- run-search! [search owner]
  (if-let [query (search/parse-query-string search)]
    (async/take! (api/search-documents query)
                 (fn [documents]
                   (println "Found documents:" documents)
                   (om/set-state! owner :documents documents)))
    (om/set-state! owner :documents nil))
  ;; Set the node's value to the right string
  (when-let [node (om/get-node owner "search")]
    (set! (.-value node) search)))

(ui/defcomponent search-ui [[state column] owner]
  (will-mount [_]
    ;; Run search (if we have a search string in the url)
    (when-let [search (:search column)]
      (run-search! search owner)))
  (will-update [_ [next-props next-column] next-state]
    ;; Run search if the search-string changes (note that an empty or
    ;; nil search-string is fine and will just clear the results)
    (let [search (:search next-column)]
      (when-not (= search (:search column))
        (run-search! search owner)))
    ;; Fetch documents after the results of the search arrive
    (when-let [documents (:documents next-state)]
      (when-not (= documents (om/get-render-state owner :documents))
        ;; TODO: Only fetch documents we don't have locally
        (api/fetch-documents! documents))))
  (render-state [_ {:keys [documents]}]
    [:.column.search
     [:header
      [:form {:on-submit (fn [e]
                           (ui/cancel-event e)
                           (let [text (.-value (om/get-node owner "search"))]
                             (replace-column! (current-columns state)
                                              [:search (:search column)] [:search text])))}
       [:input.search {:type "text"
                       :ref "search"
                       :placeholder "Search Documents"
                       :default-value (or (:search column) "")}]
       [:button {:type "submit"}
        "Search"]]]
     [:ul.search-results
      (om/build-all search-result-row (map #(get-in state [:documents %]) documents)
                    {:opts {:on-click (fn [document]
                                        (replace-column! (current-columns state)
                                                         [:search (:search column)]
                                                         [:document (:id document)]))}})]]))

(defn ^:private inbox-handle-drop! [state owner page-cache target source page-ids target-idx copy?]
  (let [columns (om/get-state owner :columns)
        target  (first (filter #(= (:id %) target) columns))
        source  (first (filter #(= (:id %) source) columns))
        ;; Remove page-ids already in `target'
        existing-pages (mapv :id (if (satisfies? ColumnSource target)
                                   (column-pages target state)
                                   []))
        pages (into []
                    (comp (remove (set existing-pages))
                          (map page-cache)
                          (map om/value))
                    page-ids)
        page-count (count existing-pages)
        ;; Handle `target-idx' being nil (by setting it to the page-count)
        target-idx (or target-idx page-count)]
    ;; NOTE: We need to handle (= idx page-count) to be able to insert pages at the end
    (when-not (<= 0 target-idx page-count)
      (throw (ex-info (str "Got invalid target-idx:" target-idx)
                      {:idx target-idx
                       :column-pages existing-pages
                       :pages pages})))
    (if-not (seq pages)
      ;; TODO: Should we do that in the columns itself?
      (js/console.warn "Ignoring drop consisting only of duplicate pages:"
                       (pr-str page-ids))
      (go
        (if (<! (accept-drop! target state pages target-idx))
          (do
            ;; TODO: Handle copy instead of move
            (println "Target saved.")
            (when-not copy?
              (println "Removing from source...")
              (<! (remove-pages! source state page-ids)))
            (println "Drop saved!"))
          (js/console.warn "`accept-drop!' returned non-truthy value. Aborting drop."))))))

(defn ^:private make-page-cache [state columns]
  (into {}
        ;; Transducerpower
        (comp (mapcat #(when (satisfies? ColumnSource %) (column-pages % state)))
              (map om/value)
              (map (juxt :id identity)))
        columns))

(defn- parse-column-param [param]
  (let [entries (s/split param #",")
        pairs (mapv #(s/split % #":") entries)]
    (for [[k v] pairs]
      (if-let [key (case k
                       "d" :document
                       "f" :file
                       "i" :inbox
                       "n" :new-document
                       "s" :search
                       nil)]
        (let [value (if (= :search key)
                      (if v (gstring/urlDecode v) "")
                      (try (let [n (js/parseInt v 10)]
                             (when (integer? n) n))
                           (catch js/Error e nil)))]
          [key value])
        (js/console.warn "Invalid column-spec: " (str k))))))

(defn- serialize-column-param [columns]
  (s/join "," (for [column columns]
                (match [column]
                  [[:document id]] (str "d:" id)
                  [[:inbox _]] "i"
                  [[:new-document _]] "n"
                  [[:search s]] (str "s:" (gstring/urlEncode (or s "")))))))

(defn- current-columns [props]
  (->> (or (get-in props [:navigation :query-params :columns])
           "i,n")
       (parse-column-param)
       (remove nil?)))

(defn- replace-column! [columns old new]
  {:pre [(vector? old) (vector? new)]}
  (nav/navigate! (nav/workflow-route
                  :inbox
                  {:columns (->> columns
                                (map #(if (= old %) new %))
                                (vec)
                                (serialize-column-param))})))

(defn- add-column! [columns column]
  {:pre [(vector? column)
         (keyword? (first column))
         (= 2 (count column))]}
  (nav/navigate! (nav/workflow-route
                  :inbox
                  (let [;; Special case: If last column is 'new
                        ;; document', insert before it
                        last-column (last columns)
                        to-add (if (= [:new-document nil] last-column)
                                 [column last-column]
                                 [last-column column])
                        columns (butlast columns)]
                    {:columns (-> columns
                                  (vec)
                                  (into to-add)
                                  (serialize-column-param))}))))

(defn- make-columns [props state owner]
  (let [columns (current-columns props)]
    (println "columns: " (pr-str columns))
    (when-not (= columns (::column-spec state))
      (om/set-state! owner ::column-spec columns)
      (let [gen (IdGenerator.getInstance)
            columns (for [column columns]
                      (match [column]
                        [[:document id]]    (->DocumentColumnSource (.getNextUniqueId gen) id)
                        [[:inbox _]]        (->InboxColumnSource (.getNextUniqueId gen))
                        [[:new-document _]] (->NewDocumentColumn (.getNextUniqueId gen))
                        [[:search s]]       (->SearchColumn (.getNextUniqueId gen) s)))]
        (filterv identity columns)))))

(defn- prepare-columns! [props state owner]
  ;; Be careful when running `set-state!' as it easily leads to loops
  (when-let [columns (make-columns props state owner)]
    (om/set-state! owner :columns columns)
    (doseq [column columns]
      (when (satisfies? om/IWillMount column)
        (om/will-mount column)))))

(ui/defcomponent inbox [state owner opts]
  (will-mount [_]
    (prepare-columns! state (om/get-state owner) owner))
  (will-update [_ next-props next-state]
    (prepare-columns! next-props next-state owner))
  (render-state [_ {:keys [columns]}]
    ;; We generate a lookup table from all known pages so `on-drop' in
    ;; `inbox-column' can access it (as the drop `dataTransfer' only
    ;; contains IDs)
    (let [page-cache (make-page-cache state columns)]
      [:.workflow.inbox
       (map (fn [x i]
              (om/build (if (special-column? x)
                          (special-column-ui x)
                          inbox-column)
                        (assoc x :om.fake/index i)
                        {:fn (fn [column]
                               [state (assoc column ::page-cache page-cache)])
                         :state {:handle-drop! (partial inbox-handle-drop! state owner page-cache)}}))
            columns
            (range))])))
