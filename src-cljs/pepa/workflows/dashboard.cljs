(ns pepa.workflows.dashboard
  (:require [om.core :as om :include-macros true]
            [nom.ui :as ui]

            [cljs.reader :as reader]
            [cljs.core.async :as async]
            [clojure.string :as s]

            [pepa.api :as api]
            [pepa.model :as model]
            [pepa.search :as search]
            [pepa.navigation :as nav]
            [pepa.style :as css]
            [pepa.selection :as selection]


            [pepa.components.document :as document]
            [pepa.components.page :as page]
            [pepa.components.tags :as tags]
            [pepa.components.draggable :as draggable])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]]))

(defn ^:private handle-tags-drop [document e]
  (when-let [tags (tags/data-transfer-tags e.dataTransfer)]
    (assert false "Unimplemented")
    ;; (api/update-document! (update-in document [:tags]
    ;;                                  #(model/add-tags % tags)))
    (.preventDefault e)))

(ui/defcomponent ^:private page-count [pages]
  (render [_]
    [:.page-count (count pages)]))

(defn- document-click [click-type document owner e]
  {:pre [(contains? #{:single :double} click-type)]}
  (when-let [clicks (om/get-state owner :clicks)]
    (async/put! clicks (assoc (selection/event->click (:id document) e)
                              ::type click-type)))
  (ui/cancel-event e))

(ui/defcomponent ^:private document-preview [document owner]
  (render [_]
    (let [href (nav/document-route (:id document))]
      ;; NOTE: We can't wrap the <a> around all those divs. It's
      ;; forbidden to put a <div> inside <a> if there are other
      ;; <a>s and other stuff inside it. If we try it nonetheless,
      ;; the browser will reorder or DOM and React will fail
      ;; horribly.
      [:.document {:on-drag-over tags/accept-tags-drop
                   :on-drop (partial handle-tags-drop document)
                   :on-click (partial document-click :single document owner)
                   :on-double-click (partial document-click :double document owner)
                   :class [(when (::selected document) "selected")]}
       [:.preview {:key "preview"}
        [:a {:href href}
         (om/build page/thumbnail (-> document :pages first) {:key :id})
         (om/build page-count (:pages document)
                   {:react-key "page-count"})]]
       [:a.title {:href href, :key "a.title"}
        (:title document)]
       (om/build tags/tags-list (:tags document)
                 {:react-key "tags-list"})])))

(ui/defcomponent ^:private sidebar-pane [[state selected-documents] owner _]
  (render [_]
    (let [sidebar-width css/default-sidebar-width
          ;; TODO/rewrite
          #_(get (om/observe owner (model/ui-sidebars)) ::sidebar
                 css/default-sidebar-width)]
      [:.pane {:key "sidebar-pane"
               :style {:min-width sidebar-width :max-width sidebar-width}}
       [:.sidebar
        [:header "Details"]
        (om/build draggable/resize-draggable nil {:opts {:sidebar ::sidebar}})

        (om/build document/document-sidebar selected-documents
                  ;; Get the real documents from IDs
                  {:fn (fn [ids]
                         (map #(get-in state [:documents %]) ids))})]])))

(defn ^:private document-ids [state]
  (:results (:search state)))

(def +initial-elements+ 50)
(def +to-load+ 50)

(defn ^:private parse-query-number [state name & [default]]
  (or
   (try
     (let [n (some-> (get-in state [:navigation :query-params name])
                     (js/parseInt 10))]
       (and (integer? n) n))
     (catch js/Error e nil))
   default
   0))

(defn ^:private parse-count-scroll
  [state]
  [(parse-query-number state :count +initial-elements+)
   (parse-query-number state :scroll 0)])

(defn ^:private page-ids [state]
  (let [[n _] (parse-count-scroll state)]
    (take n (document-ids state))))

(ui/defcomponent ^:private document-count [document-ids]
  (render [_]
    [:span.document-count
     (str "(" (count document-ids) ")")]))

(ui/defcomponent ^:private dashboard-title [search owner opts]
  (render [_]
    (let [documents (:results search)
          active-search? (search/search-active? search)]
      [:span
       (cond
         active-search?
         "Loading..."

         (search/all-documents? search)
         "All Documents"

         (:query search)
         "Search Results"

         true
         "Dashboard")
       (when-not active-search?
         (om/build document-count documents
                   {:react-key "document-count"}))])))

;;; TODO/refactor
(comment
;;; Should be twice the document-height or so.
  (def +scroll-margin+ 500)

  (defn ^:private on-documents-scroll [state owner e]
    (let [container e.currentTarget
          scroll-top (.-scrollTop container)
          scroll-height (.-scrollHeight container)
          outer-height (.-clientHeight container)
          bottom-distance (Math/abs (- scroll-height
                                       (+ scroll-top outer-height)))
          progress (/ scroll-top scroll-height)
          elements (document-ids state)]
      (let [[num scroll] (parse-count-scroll state)
            scroll (Math/ceil (* progress num))
            num (if (< bottom-distance +scroll-margin+)
                  (+ num +to-load+)
                  num)
            num (min num (count elements))]
        (-> (:navigation state)
            (assoc-in [:query-params :count] (str num))
            (assoc-in [:query-params :scroll] (str scroll))
            (nav/nav->route)
            (nav/nnavigate! :ignore-history :no-dispatch))
        (om/update! state [:navigation :query-params :count] num))))

  (defn ^:private scroll-to-offset! [state owner]
    (let [el (om/get-node owner "documents")
          scroll-height (.-scrollHeight el)
          [num scroll] (parse-count-scroll state)
          elements (page-ids state)]
      (cond
        (= 0 (.-scrollTop el))
        (set! (.-scrollTop el) (* scroll-height
                                  (/ scroll (count elements))))
        ;; TODO: :count isn't always nil (the query-params won't get
        ;; updated). Need another way to change the url without changing
        ;; history
        (every? nil? ((juxt :count :scroll) (get-in state [:navigation :query-params])))
        (set! (.-scrollTop el) 0)))))

(defn ^:private click-loop! [state owner]
  (go-loop []
    (when-let [click (<! (om/get-state owner :clicks))]
      (if (= :double (::type click))
        (nav/navigate! (nav/document-route (:element click)))
        (om/update-state! owner :selection (fn [selection]
                                             (selection/click selection click))))
      (recur))))

;; (defn on-document-click [state owner document])

(ui/defcomponent dashboard [state owner]
  om/ICheckState
  (init-state [_]
    {:selection (selection/make-selection (document-ids state))
     :clicks (async/chan)})
  (will-mount [_]
    (click-loop! state owner))
  (will-receive-props [_ new-state]
    (when (not= (document-ids (om/get-props owner))
                (document-ids new-state))
      (om/set-state! owner :selection
                     (selection/make-selection (document-ids new-state)))))
  ;; TODO/refactor
  ;; (did-update [_ _ _]
  ;;   (scroll-to-offset! state owner))
  (render-state [_ {:keys [working? selection clicks]}]
    (let [document-ids (page-ids state)
          search (:search state)
          working? (or working? (search/search-active? search))]
      [:.workflow.dashboard
       [:.pane {:key "documents-pane"}
        [:header {:key "header"}
         (om/build dashboard-title search {:react-key "title"})]
        [:.documents {:ref "documents"
                      :key "documents"
                      ;; TODO/refactor
                      ;; :on-scroll (partial on-documents-scroll state owner)
                      :class [(when working? "working")]}
         (let [documents (into []
                               (keep (partial get (:documents state)))
                               document-ids)]
           (om/build-all document-preview documents
                         {:key :id
                          ;; This is way more performant than passing
                          ;; :selection via :state
                          :fn #(assoc % ::selected
                                      (contains? (:selected selection) (:id %)))
                          :state {:clicks clicks}}))]]
       (om/build sidebar-pane [state (:selected selection)])])))

(defmethod draggable/pos->width ::sidebar [_ sidebar [x _]]
  (draggable/limit
   (- (draggable/viewport-width) x)))
