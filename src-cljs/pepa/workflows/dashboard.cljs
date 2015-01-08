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
            [pepa.components.pagination :as pagination])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]]))

(defn ^:private handle-tags-drop [document e]
  (when-let [tags (tags/data-transfer-tags e.dataTransfer)]
    (api/update-document! (update-in document [:tags]
                                     #(data/add-tags % tags)))
    (.preventDefault e)))

(defn document-preview [document]
  (reify
    om/IRender
    (render [_]
      (let [href (nav/document-route document)]
        (html
         [:.container
          [:.document {:on-drag-over tags/accept-tags-drop
                       :on-drop (partial handle-tags-drop document)}
           [:.preview
            [:a {:href href}
             (om/build page/thumbnail (-> document :pages first))]]
           [:a.title {:href href}
            (:title document)]
           (om/build tags/tags-list (:tags document))]])))))

(defn filter-sidebar [state]
  (reify
    om/IRenderState
    (render-state [_ _]
      (html
       [:.sidebar
        [:header "Sorting & Filtering"]]))))

(defn ^:private document-ids [state]
  (or (:search/results state)
      ;; Default to all documents
      (keys (:documents state))))

(defn ^:private parse-page
  "Returns the current page (according to the page query-param
  of :navigation) or 1, if none given."
  [state]
  (or
   (try
     (some-> state
             :navigation :query-params :page
             (js/parseInt 10))
     (catch js/Error e nil))
   1))

(def +per-page+ 20)

(defn ^:private page-ids [state]
  (let [page (parse-page state)]
    (take (* +per-page+ page) (document-ids state))))

(defn ^:private fetch-missing-documents!
  "Fetches all missing documents which will be visible in the
  documents-overview."
  [state owner]
  (go
    (let [ids (page-ids state)
          missing (remove (set (keys (:documents state))) ids)]
      (when (seq missing)
        (<! (api/fetch-documents! missing))))))

(defn ^:private search-maybe! [state owner]
  (match [(-> @state :navigation :route)]
    [[:search [:tag tag]]]
    (search/search! state (list 'tag tag))
    [[:search [:query query]]]
    (search/search! state query) 
    :else
    (go (search/clear-results! state))))

(defn ^:private dashboard-title [state]
  (let [documents (document-ids state)]
    (cond
     (search/search-query state)
     "Search Results"
    
     true
     "Dashboard")))

;;; Should be twice the document-height or so.
(def +scroll-margin+ 400)

(defn on-documents-scroll [state owner e]
  (let [container e.currentTarget
        scroll-top (.-scrollTop container)
        scroll-height (.-scrollHeight container)
        outer-height (.-clientHeight container)
        bottom-distance (Math/abs (- scroll-height
                                     (+ scroll-top outer-height)))]
    (when (< bottom-distance +scroll-margin+)
      (let [page (parse-page state)]
        (nav/navigate! (-> (:navigation state)
                           (assoc-in [:query-params :page] (inc page))
                           (nav/nav->route)
                           (nav/navigate!)))))))

(defn dashboard [state owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (go
        (<! (search-maybe! state owner))
        (<! (fetch-missing-documents! state owner))))
    om/IDidUpdate
    (did-update [_ _ _]
      (go
        (<! (search-maybe! state owner))
        (<! (fetch-missing-documents! state owner))))
    om/IInitState
    (init-state [_]
      {:filter-width css/default-right-sidebar-width})
    om/IRenderState
    (render-state [_ local-state]
      ;; Show all documents with ids found in :dashboard/document-ids
      (let [document-ids (page-ids state)]
        (html
         [:.workflow.dashboard
          [:.pane {:style {:width (str "calc(100% - " (:filter-width local-state) "px - 2px)")}}
           [:header
            (dashboard-title state)]
           [:.documents {:class ["col-3"]
                         :on-scroll (partial on-documents-scroll state owner)}
            (let [documents (->> document-ids
                                 (map (partial get (:documents state)))
                                 (remove nil?))]
              (om/build-all document-preview documents
                            {:key :id}))]]
          [:.pane {:style {:width (:filter-width local-state)}}
           (om/build filter-sidebar state)]])))))
