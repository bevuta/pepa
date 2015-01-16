(ns pepa.workflows.dashboard
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [cljs.reader :as reader]
            [cljs.core.async :as async]

            [pepa.api :as api]
            [pepa.data :as data]
            [pepa.search :as search]
            [pepa.navigation :as nav]
            [clojure.string :as s]

            [pepa.style :as css]

            [pepa.components.page :as page]
            [pepa.components.tags :as tags]
            [pepa.components.draggable :refer [resize-draggable
                                               right-align-xform]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]]))

(defn ^:private handle-tags-drop [document e]
  (when-let [tags (tags/data-transfer-tags e.dataTransfer)]
    (api/update-document! (update-in document [:tags]
                                     #(data/add-tags % tags)))
    (.preventDefault e)))

(defn ^:private page-count [pages]
  (om/component
   (html
    [:.page-count (count pages)])))

(defn document-preview [document]
  (reify
    om/IRender
    (render [_]
      (let [href (nav/document-route document)]
        (html
         [:.document {:on-drag-over tags/accept-tags-drop
                      :on-drop (partial handle-tags-drop document)}
          [:.preview
           [:a {:href href}
            (om/build page/thumbnail (-> document :pages first))
            (om/build page-count (:pages document))]]
          [:a.title {:href href}
           (:title document)]
          (om/build tags/tags-list (:tags document))])))))

(defn filter-sidebar [state owner _]
  (reify
    om/IInitState
    (init-state [_]
      {:xs (async/chan (async/sliding-buffer 1)
                       right-align-xform)})
    om/IWillMount
    (will-mount [_]
      (async/pipe (om/get-state owner :xs)
                  (om/get-state owner :widths)))
    om/IRenderState
    (render-state [_ {:keys [xs]}]
      (html
       [:.sidebar
        [:header "Sorting & Filtering"]
        (om/build resize-draggable nil {:init-state {:xs xs}})]))))

(defn ^:private document-ids [state]
  (or (:search/results state)
      ;; Default to all documents
      (keys (:documents state))))

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
        (<! (api/fetch-documents! missing))))))

(defn ^:private search-maybe! [state owner & [force-update?]]
  (match [(-> @state :navigation :route)]
    [[:search [:tag tag]]]
    (search/search! state (list 'tag tag))
    [[:search [:query query]]]
    (search/search! state query) 
    :else
    (go (search/all-documents! state force-update?))))

(defn ^:private dashboard-title [state]
  (let [documents (document-ids state)]
    (cond
     (search/search-query state)
     "Search Results"
    
     true
     "Dashboard")))

(defn ^:private document-count [state]
  (om/component
   (html
    [:span.document-count (str "(" (count (document-ids state)) ")")])))

;;; Should be twice the document-height or so.
(def +scroll-margin+ 500)

(defn on-documents-scroll [state owner e]
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

(defn scroll-to-offset! [state owner]
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

(defn dashboard [state owner]
  (reify
    om/ICheckState
    om/IInitState
    (init-state [_]
      {:filter-width css/default-sidebar-width
       :widths (async/chan (async/sliding-buffer 1))})
    om/IWillMount
    (will-mount [_]
      (go
        (<! (search-maybe! state owner :force-update))
        (<! (fetch-missing-documents! state owner))
        (scroll-to-offset! state owner))
      ;; Sidebar width handling
      (go-loop []
        (when-let [width (<! (om/get-state owner :widths))]
          (when (<= css/min-sidebar-width width css/max-sidebar-width)
            (om/set-state! owner :filter-width width))
          (recur))))
    om/IDidUpdate
    (did-update [_ _ _]
      (go
        (<! (search-maybe! state owner))
        (<! (fetch-missing-documents! state owner))
        (scroll-to-offset! state owner)))
    om/IRenderState
    (render-state [_ local-state]
      ;; Show all documents with ids found in :dashboard/document-ids
      (let [document-ids (page-ids state)]
        (html
         [:.workflow.dashboard
          [:.pane {:style {:width (str "calc(100% - " (:filter-width local-state) "px - 2px)")}}
           [:header
            (dashboard-title state)
            (om/build document-count state)]
           [:.documents {:ref "documents"
                         :on-scroll (partial on-documents-scroll state owner)}
            (let [documents (->> document-ids
                                 (map (partial get (:documents state)))
                                 (remove nil?))]
              (om/build-all document-preview documents
                            {:key :id}))]]
          [:.pane {:style {:width (:filter-width local-state)}}
           (om/build filter-sidebar state
                     {:init-state {:widths (:widths local-state)}})]])))))
