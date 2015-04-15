(ns pepa.workflows.dashboard
  (:require [om.core :as om :include-macros true]
            [cljs.reader :as reader]
            [cljs.core.async :as async]
            [clojure.string :as s]
            
            [pepa.api :as api]
            [pepa.data :as data]
            [pepa.search :as search]
            [pepa.navigation :as nav]
            [pepa.style :as css]
            [nom.ui :as ui]

            [pepa.components.page :as page]
            [pepa.components.tags :as tags]
            [pepa.components.draggable :as draggable])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]]))

(defn ^:private handle-tags-drop [document e]
  (when-let [tags (tags/data-transfer-tags e.dataTransfer)]
    (api/update-document! (update-in document [:tags]
                                     #(data/add-tags % tags)))
    (.preventDefault e)))

(ui/defcomponent ^:private page-count [pages]
  (render [_]
    [:.page-count (count pages)]))

(ui/defcomponent ^:private document-preview [document]
  (render [_]
    (let [href (nav/document-route document)]
      ;; NOTE: We can't wrap the <a> around all those divs. It's
      ;; forbidden to put a <div> inside <a> if there are other
      ;; <a>s and other stuff inside it. If we try it nonetheless,
      ;; the browser will reorder or DOM and React will fail
      ;; horribly.
      [:.document {:on-drag-over tags/accept-tags-drop
                   :on-drop (partial handle-tags-drop document)
                   :on-click (fn [e]
                               (nav/navigate! (nav/document-route document))
                               (doto e
                                 (.stopPropagation)
                                 (.preventDefault)))}
       [:.preview {:key "preview"}
        [:a {:href href}
         (om/build page/thumbnail (-> document :pages first) {:key :id})
         (om/build page-count (:pages document)
                   {:react-key "page-count"})]]
       [:a.title {:href href, :key "a.title"}
        (:title document)]
       (om/build tags/tags-list (:tags document)
                 {:react-key "tags-list"})])))

(ui/defcomponent ^:private sidebar-pane [state owner _]
  (render [_]
    (let [sidebar-width (get (om/observe owner (data/ui-sidebars)) ::sidebar
                             css/default-sidebar-width)]
      [:.pane {:key "sidebar-pane"
               :style {:min-width sidebar-width :max-width sidebar-width}}
       [:.sidebar
        [:header "Sorting & Filtering"]
        (om/build draggable/resize-draggable nil {:opts {:sidebar ::sidebar}})]])))

(defn ^:private document-ids [state]
  (:results (search/current-search state)))

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

(defn ^:private fetch-missing-documents!
  "Fetches all missing documents which will be visible in the
  documents-overview."
  [state owner]
  (go
    (let [ids (page-ids state)
          missing (remove (set (keys (:documents state))) ids)]
      (when (seq missing)
        (println "fetching missing documents:" missing)
        (<! (api/fetch-documents! missing))))))

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
          (nav/navigate! :ignore-history :no-dispatch))
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
      (set! (.-scrollTop el) 0))))

(ui/defcomponent dashboard [state owner]
  om/ICheckState
  (will-mount [_]
    (go
      (ui/with-working owner
        (<! (fetch-missing-documents! state owner)))))
  (will-receive-props [_ new-state]
    (go
      (ui/with-working owner
        (<! (fetch-missing-documents! new-state owner)))))
  (did-update [_ _ _]
    (scroll-to-offset! state owner))
  (render-state [_ {:keys [working?]}]
    (let [document-ids (page-ids state)
          search (search/current-search state)
          working? (or working? (search/search-active? search))]
      [:.workflow.dashboard
       [:.pane {:key "documents-pane"}
        [:header {:key "header"}
         (om/build dashboard-title search {:react-key "title"})]
        [:.documents {:ref "documents"
                      :key "documents"
                      :on-scroll (partial on-documents-scroll state owner)
                      :class [(when working? "working")]}
         (let [documents (->> document-ids
                              (map (partial get (:documents state)))
                              (remove nil?))]
           (om/build-all document-preview documents
                         {:key :id}))]]
       (om/build sidebar-pane state)])))

(defmethod draggable/pos->width ::sidebar [_ sidebar [x _]]
  (draggable/limit
   (- (draggable/viewport-width) x)))
