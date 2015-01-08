(ns pepa.workflows.inbox
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom]
            [sablono.core :refer-macros [html]]

            [clojure.string :as s]
            [cljs.core.async :as async :refer [<!]]
            [cljs.core.match]

            [pepa.components.utils :as utils]
            [pepa.api :as api]
            [pepa.data :as data]

            [pepa.components.page :as page]
            [pepa.components.tags :as tags])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]])
  (:import [cljs.core.ExceptionInfo]))

;;; Utility Functions

(defn ^:private inbox?
  "Returns true if DOCUMENT is the inbox (for special-handling)."
  [document]
  (= :inbox (:id document)))

(defn ^:private page-idx
  "Returns the index of PAGE in PAGES or nil."
  [pages page]
  (some #(when (= page (second %)) (first %))
        (map-indexed vector pages)))

;;; Selection Handling

(defn ^:private select-document
  "Selects the document in selection. Clears the set of selected pages
  when it's a different document."
  [selection document-id]
  (if-not (= document-id (:document selection))
    (assoc selection
      :document document-id
      :last-page nil
      :pages #{})
    selection))

(defn ^:private select-page
  "Selects or deselects a page in a document. Also handles switching
  to a different document."
  [selection document-id page clear-selection?]
  (-> selection
      (select-document document-id)
      (assoc :last-page page)
      (update-in [:pages]
                 (fn [pages]
                   (cond
                    clear-selection?
                    (if (contains? pages page)
                      #{}
                      #{page})

                    (contains? pages page)
                    (disj pages page)

                    true
                    (conj pages page))))))

(defn ^:private select-page-always
  "Like `select-page', but will never deselect the page."
  [selection document-id page]
  (-> selection
      (select-document document-id)
      (update-in [:pages] conj page)))

(defn ^:private page-range [pages from-page to-page]
  (let [indexed (map-indexed #(assoc %2 ::idx %1) [from-page to-page])
        page-idx (partial page-idx (om/value pages))
        ;; Make sure from-page is always before to-page
        [from-page to-page] (if (< (page-idx to-page)
                                   (page-idx from-page))
                              [to-page from-page]
                              [from-page to-page])]
    (concat
     (->> pages
          (drop-while (complement #{from-page}))
          (take-while (complement #{to-page})))
     [to-page])))

(defn ^:private select-page-range
  "Selects all pages up to PAGE (incl.), starting from (:last-page
  selection)."
  [selection document-id page all-pages]
  (let [from-page (:last-page selection)]
    (-> selection
        (select-document document-id)
        (update-in [:pages] #(into % (page-range all-pages from-page page))))))

;;; Page click/drag handlers

(defn ^:private event-keys [e]
  (->> [(when (.-ctrlKey e) :key/control)
        (when (.-shiftKey e) :key/shift)
        (when (.-metaKey e) :key/meta)
        (when (.-altKey e) :key/alt)]
       (remove nil?)
       (set)))

(defn ^:private page-click [page owner e]
  (async/put! (om/get-state owner :events)
              {:event :page-click
               :page (om/value page)
               :keys (event-keys e)}))

;; (defn ^:private page-drag-over [page owner e]
;;   (async/put! (om/get-state owner :events)
;;               {:event :drag-target
;;                :type :page
;;                :page (om/value page)
;;                :position (let [rect (.getBoundingClientRect (.-currentTarget e))
;;                                top (.-top rect)
;;                                height (.-height rect)
;;                                local (Math/abs (- (.-clientY e) top))]
;;                            (if (>= (/ height 2) local) :above :below))})
;;   false)

;; (defn ^:private page-drag-start [page owner e]
;;   (async/put! (om/get-state owner :events)
;;               {:event :page-drag-start
;;                :page (om/value page)})
;;   true)

;; ;;; Document Handlers

;; (defn ^:private document-drag-over [document owner e]
;;   (async/put! (om/get-state owner :events)
;;               {:event :drag-target
;;                :type :document
;;                :document-id (:id document)})
;;   false)

;;;; Document component

;;; Save Button

(defn save-button [document owner _]
  (reify
    om/IDisplayName (display-name [_] "SaveButton")
    om/IRenderState
    (render-state [_ {:keys [disabled?]}]
      (html
       [:button.save {:on-click (fn [e]
                                  (async/put! (om/get-state owner :events)
                                              {:event :document/save
                                               :document (om/value document)})
                                  (.preventDefault e))
                      :disabled disabled?}
        "Save"]))))

;;; Button to refresh the inbox

(defn update-inbox-button [inbox owner _]
  (reify
    om/IDisplayName (display-name [_] "UpdateInboxButton")
    om/IRender
    (render [_]
      (html
       [:button {:class "refresh"
                 :disabled (::working inbox)
                 :on-click (fn [e]
                             (async/put! (om/get-state owner :events)
                                         {:event :inbox/update})
                             (.preventDefault e))}
        "Refresh"]))))

;;; Editable Document Title


(defn ^:private inbox-title [inbox owner _]
  (om/component
   (html
    [:header.inbox
     [:.title (or (:title inbox)
                  "Unnamed")]
     (om/build update-inbox-button inbox
               {:init-state {:events (om/get-state owner :events)}})])))

(defn ^:private collapsible-document-props [document owner _]
  (reify
    om/IDisplayName (display-name [_] "CollapsibleDocumentProps")
    om/IInitState
    (init-state [_]
      {:collapsed? true})
    om/IRenderState
    (render-state [_ {:keys [collapsed?]}]
      (html
       [:.collapse {:class [(if collapsed? "collapsed" "open")]
                    :on-click (fn [e]
                                (om/update-state! owner :collapsed? not)
                                (doto e
                                  (.preventDefault)
                                  (.stopPropagation)))}
        (when-not collapsed?
          (om/build tags/tags-input document))]))))

(defn ^:private editable-title [document owner _]
  (reify
    om/IDisplayName (display-name [_] "EditableTitle")
    om/IInitState
    (init-state [_]
      {:editing? false})
    om/IDidUpdate
    (did-update [_ _ prev-state]
      ;; Focus title-input when visible
      (utils/focus-if owner prev-state :editing? "input" :move-caret))
    om/IRenderState
    (render-state [_ {:keys [editing? events]}]
      (html
       [:header {:on-click (when-not editing?
                             (fn [e]
                               (om/set-state! owner :editing? true)
                               (.stopPropagation e)))}
        (let [title (:title document)
              title (when-not (s/blank? title) title)]
          (if-not editing?
            (list
             [:.title {:key :title}
              (or title "Unnamed")]
             (om/build save-button document
                       {:init-state {:events events}
                        :state {:disabled? (or (s/blank? title)
                                               (empty? (:pages document)))}
                        :key :id}))
            (list
             [:form.title {:on-submit (fn [e]
                                        (om/set-state! owner :editing? false)
                                        (.preventDefault e))}
              [:input {:type :text
                       :value title
                       :ref "input"
                       :autofocus true
                       :placeholder "Unnamed"
                       :on-change (fn [e]
                                    (let [title e.target.value]
                                      (om/update! document :title
                                                  (when-not (s/blank? title) title))))
                       :on-blur #(om/set-state! owner :editing? false)}]
              [:button.ok {:type "submit"}
               "Ok"]])))
        (om/build collapsible-document-props document)]))))

;;; A single page

(defn ^:private document-page [page owner _]
  (reify
    om/IDisplayName (display-name [_] "DocumentPage")
    om/IInitState
    (init-state [_]
      {:selected? false
       ;; :drag-active? false
       ;; :drag-target nil
                                        ;nil, :above, :below
       })
    om/IRenderState
    (render-state [_ {:keys [selected? view ;; drag-active? drag-target
                             ]}]
      (let [id (:id page)]
        ;; Use om.dom for performance
        (dom/li #js {:key id, :ref id
                     ;; :draggable true
                     :className (s/join " " ["page"
                                             (when selected? "selected")
                                             ;; (when (and drag-active? selected?) "dragging")
                                             ;; (when drag-target "target")
                                             ;; (when drag-target (name drag-target))
                                             ])
                     :onClick (partial page-click page owner)
                     ;; :onDragStart (partial page-drag-start page owner)
                     ;; :onDragOver (partial page-drag-over page owner)
                     ;; ;; Bubble the drop event
                     ;; :onDrop (constantly true)
                     }
                (om/build view page {:key :id}))))))

;;; Document Column

(defn ^:private document-pull-pages! [document owner e]
  (async/put! (om/get-state owner :events)
              {:event :document/pull-pages
               :document (om/value document)}))

(defn ^:private document-remove-pages! [document owner e]
  (async/put! (om/get-state owner :events)
              {:event :document/pull-pages
               ;; HACK
               :document {:id :inbox}}))

(defn ^:private document-delete-pages! [document owner e]
  (async/put! (om/get-state owner :events)
              {:event :inbox/delete-pages}))

(defn document [document owner _]
  (reify
    om/IDisplayName (display-name [_] "DocumentColumn")
    om/IInitState
    (init-state [_]
      {:page-events (async/chan 1 (map #(let [d @document]
                                          (assoc %
                                                 :document-id (-> d :id om/value)
                                                 :pages (-> d :pages om/value)))))})
    om/IWillMount
    (will-mount [_]
      (async/pipe (om/get-state owner :page-events)
                  (om/get-state owner :events)))
    om/IRenderState
    (render-state [_ _]
      (let [pages (:pages document)
            state (om/get-state owner)
            selected-pages (:selection/pages state)
            selected-document (:selection/document state)
            view (:view state)

            ;; Drag Handling
            ;; drag-active? (:drag/active? state)
            ;; drag-target? (:drag-target? state)
            ;; target-page (:drag/target-page state)
            ;; target-position (:drag/target-position state)
                                        ; :above/:below
            ]
        (assert (fn? view))
        (html
         [:td
          [:.document { ;; :on-drag-over (partial document-drag-over document owner)
                       ;; :on-drop (constantly true)
                       :class [ ;; (when (and drag-target? (nil? target-page)) "target")
                               (when (inbox? document) "inbox")
                               ;; Footer Visibility
                               (when (seq selected-pages)
                                 "footer-visible")]}
           (let [init-state (select-keys state [:events])]
             (if (inbox? document)
               (om/build inbox-title document
                         {:key :id
                          :init-state init-state})
               (om/build editable-title document
                         {:init-state init-state
                          :key :id})))
           [:ul.pages {:key :pages}
            (let [page-events (:page-events state)]
              (doall
               (for [p pages]
                 (om/build document-page p
                           {:key :id
                            :init-state {:view view
                                         :events page-events}
                            :state {:selected? (and (contains? selected-pages p)
                                                    (= selected-document (:id document)))
                                    ;; :drag-active? drag-active?
                                    ;; :drag-target (when (= target-page p)
                                    ;;                target-position)
                                    }}))))]
           [:footer
            (if (inbox? document)
              [:button.delete {:on-click (partial document-delete-pages! document owner)}
               "Delete Pages"]
              (list
               (when (not= selected-document (:id document))
                 [:button.move {:on-click (partial document-pull-pages! document owner)}
                  "Move Pages"])
               (when (= selected-document (:id document))
                 [:button.remove {:on-click (partial document-remove-pages! document owner)}
                  "Remove Pages"])))]]])))))

;;; The "Drag Target" Column

(defn ^:private create-document! [owner e]
  (async/put! (om/get-state owner :events)
              {:event :new-document}))

(defn create-document-column [_ owner _]
  (reify
    om/IDisplayName (display-name [_] "CreateDocumentColumn")
    om/IRenderState
    (render-state [_ _]
      (let [selected-pages (om/get-state owner :selection/pages)]
        (html
         [:td.create-document { ;; :on-drag-over (partial new-document-drag-over state owner)
                               :key :create-document
                               :class [ ;; (when (= ::new-document (:target drag))
                                       ;;   "active")
                                       ]}
          [:.note
           ;; [:img {:src "/img/drop-arrow.svg"}]
           [:.arrow]
           [:button.create {:on-click (partial create-document! owner)
                            :disabled (empty? selected-pages)}
            "Create New Document"]]])))))

;;; Drag/DropHandling

(defn ^:private move-pages [documents pages from to & [target-page target-position]]
  (println "moving" pages "from" from "to" to
           (str "target: (" target-page ", " target-position ")"))
  (assert from)
  (assert to)
  (assert (seq pages))
  (-> documents
      (update-in [from] #(data/remove-pages % pages))
      (update-in [to]
                 (fn [document]
                   (data/add-pages document pages
                                   (or target-position :before)
                                   target-page)))))

(defn new-document [documents pages & [from]]
  (when pages (assert from))
  (let [document (data/map->Document {:id (gensym)
                                      :pages []})
        documents (assoc documents (:id document) document)]
    (if from
      (move-pages documents pages from (:id document))
      documents)))

;; (defn ^:private workflow-drop [state owner e]
;;   (async/put! (om/get-state owner :events)
;;               {:event :drop})
;;   false)

;; (defn ^:private workflow-drag-end [state owner e]
;;   (om/update! state [:drag] {})
;;   false)

;; (defn new-document-drag-over [state owner e]
;;   (async/put! (om/get-state owner :events)
;;               {:event :drag-target
;;                :type ::new-document})
;;   false)

;; (defn new-document-drop [state owner e]
;;   (js/console.log e))

;;; Workflow component

;;; TODO: Automatically create empty/new documents
(def initial-data {:documents {:inbox (data/map->Document {:id :inbox
                                                           :title "Inbox"
                                                           :pages []})}})

(defn ^:private update-inbox!
  "Updates the Inbox from the server."
  [state]
  (go
    (try
      (om/update! state [:documents :inbox ::working] true)
      (let [new-inbox (vec (<! (api/fetch-inbox)))
            ;; We need all already-filed pages so we don't show them
            ;; again (Deref because we need the *current* state here)
            filed-pages (mapcat :pages (vals (:documents @state)))]
        (om/transact! state [:documents :inbox :pages]
                      (fn [pages]
                        (let [existing-pages (concat pages filed-pages)]
                          (->> new-inbox
                               (remove (set existing-pages))
                               (concat pages)
                               (vec))))))
      (finally
        (om/transact! state [:documents :inbox] #(dissoc % ::working))))))

(defn ^:private save-document!
  "Saves DOCUMENT on the server and removes it from the inbox state."
  [state document]
  (go
    (assert (:title document))
    (assert (seq (:pages document)))
    (println "Saving document" document)
    (when (<! (api/save-new-document! document "inbox"))
      (om/transact! state :documents #(dissoc % (:id document)))
      (let [pages (:pages document)]
        (when (<! (api/delete-from-inbox! pages))
          (om/transact! state [:documents :inbox :pages] #(remove (set pages) %)))))))

(defn ^:private delete-pages!
  "Deletes selected pages from the inbox on the server."
  [state pages]
  (go
    (try
      (om/update! state [:documents :inbox ::working] true)
      (println "Deleting pages" pages "from inbox...")
      (when (<! (api/delete-from-inbox! pages))
        (om/transact! state [:documents :inbox :pages] #(remove (set pages) %)))
      (finally
        (om/transact! state [:documents :inbox] #(dissoc % ::working))))))

(defn group-pages-workflow [state owner]
  (reify
    om/IDisplayName (display-name [_] "GroupPagesWorkflow")
    om/IInitState
    (init-state [_]
      {:events (async/chan)})
    om/IWillMount
    (will-mount [_]
      ;; Fetch inbox documents
      (update-inbox! state)
      ;; Start the event-loop (handling events from the children)
      (go-loop []
        (when-let [event (<! (om/get-state owner :events))]
          ;; (println "got event:" event)
          (match [event]
            [{:event :page-click
              :document-id did
              :page page
              :keys keys}]
            (om/transact! state :selection
                          (fn [selection]
                            (if (contains? keys :key/shift)
                              (select-page-range selection
                                                 did
                                                 page
                                                 (:pages event))
                              (select-page selection
                                           did
                                           page
                                           (not (some #{:key/control :key/meta} keys))))))

            ;; [{:event :page-drag-start}]
            ;; (doto state
            ;;   (om/transact! :selection
            ;;                 #(select-page-always %
            ;;                                      (:document-id event)
            ;;                                      (:page event)))
            ;;   (om/update! [:drag :active?] true))

            ;; [{:event :drag-target, :type :page}]
            ;; (if-not (contains? (-> @state :selection :pages) (:page event))
            ;;   (om/update! state [:drag :target] {:document (:document-id event)
            ;;                                      :page (:page event)
            ;;                                      :position (:position event)})
            ;;   (js/console.warn "target-page is a dragged page!"))

            ;; [{:event :drag-target, :type :document}]
            ;; (om/update! state [:drag :target] {:document (:document-id event)
            ;;                                    :page nil
            ;;                                    :position nil})

            ;; [{:event :drag-target, :type ::new-document}]
            ;; (om/update! state [:drag :target] ::new-document)

            ;; [{:event :drop}]
            ;; ;; NOTE(mu): This is super ugly - but drag&drop is utterly broken in browsers
            ;; (try
            ;;   (let [current-state @state
            ;;         pages (get-in current-state [:selection :pages])
            ;;         from  (get-in current-state [:selection :document]) 
            ;;         target (get-in current-state [:drag :target])
            ;;         ;; Sort pages by their position in FROM
            ;;         pages (sort-by (partial page-idx (get-in current-state [:documents from :pages]))
            ;;                        pages)]
            ;;     (when-not (seq pages)
            ;;       (throw (ex-info "Drop contains no pages!" {})))
            ;;     (when-not from
            ;;       (throw (ex-info "Drag has no from" {})))
            ;;     (when-not target
            ;;       (throw (ex-info "Drag has no target" {})))
            ;;     (cond
            ;;      ;; Create a new document
            ;;      (= ::new-document (get-in current-state [:drag :target]))
            ;;      (om/transact! state :documents #(new-document % pages from))

            ;;      ;; Target is a document
            ;;      target
            ;;      (let [to (:document target)]
            ;;        (when-not to
            ;;          (throw (ex-info "Drag has no valid target document" {})))
            ;;        (when (contains? (set pages) (:page target))
            ;;          (throw (ex-info "Target page is a moving page" {})))
            ;;        (om/transact! state :documents #(move-pages % pages from to
            ;;                                                    (:page target)
            ;;                                                    (case (:position target)
            ;;                                                      :above :before
            ;;                                                      :below :after
            ;;                                                      nil))))

            ;;      true
            ;;      (throw (ex-info "Drag has an invalid target" {}))))
            ;;   (catch ExceptionInfo e
            ;;     (js/console.error (ex-message e)
            ;;                       (pr-str (ex-data e))))
            ;;   (finally
            ;;     (om/update! state :drag {})
            ;;     (om/update! state :selection {})))

            [{:event :new-document}]
            (try
              (let [current-state @state
                    pages (get-in current-state [:selection :pages])
                    from  (get-in current-state [:selection :document]) 
                    ;; Sort pages by their position in FROM
                    pages (sort-by (partial page-idx (get-in current-state [:documents from :pages]))
                                   pages)]
                (when (and from (seq pages))
                  (om/transact! state :documents #(new-document % pages from))))
              (finally                           
                (om/update! state :selection {})))

            [{:event :document/pull-pages}]
            (try
              (let [current-state @state
                    pages (get-in current-state [:selection :pages])
                    from  (get-in current-state [:selection :document]) 
                    ;; Sort pages by their position in FROM
                    pages (sort-by (partial page-idx (get-in current-state [:documents from :pages]))
                                   pages)
                    to (-> event :document :id)]
                (when (and from to (seq pages))
                  (om/transact! state :documents #(move-pages % pages from to))))
              (finally                           
                (om/update! state :selection {})))

            [{:event :inbox/update}]
            (update-inbox! state)

            [{:event :document/save, :document document}]
            (save-document! state document)

            [{:event :inbox/delete-pages}]
            (try
              (let [pages (get-in @state [:selection :pages])]
                (delete-pages! state pages))
              (finally
                (om/update! state :selection {})))) 
          (recur))))
    om/IRenderState
    (render-state [_ {:keys [events]}]
      (html
       [:.workflow.inbox { ;; :on-drag-end (partial workflow-drag-end state owner)
                          ;; :on-drop (partial workflow-drop state owner)
                          }
        [:table
         [:tr
          (let [inbox (-> state :documents :inbox)
                documents (dissoc (:documents state) :inbox)

                selection (:selection state)
                drag (:drag state)
                
                child-state {:selection/pages (:pages selection)
                             :selection/document (:document selection)}]
            (list
             (om/build-all document (concat [inbox] (vals documents))
                           {:key :id
                            :init-state {:view page/thumbnail
                                         :events events}
                            ;; We pass the drag/state information down
                            ;; the render tree via :state
                            ;;
                            ;; NOTE: We need to make sure the values are
                            ;; *always* passed down, as om/build will
                            ;; just *merge* the map, not override it
                            :state child-state})
             (om/build create-document-column nil
                       {:init-state {:events events}
                        :state child-state})))]]]))))
;;; 
