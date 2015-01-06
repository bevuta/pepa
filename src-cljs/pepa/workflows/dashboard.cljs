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
         [:.document {:on-drag-over tags/accept-tags-drop
                      :on-drop (partial handle-tags-drop document)}
          [:.preview
           [:a {:href href}
            (om/build page/thumbnail (-> document :pages first))]]
          [:a.title {:href href}
           (:title document)]
          (om/build tags/tags-list (:tags document))])))))

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

(defn ^:private page-ids [state]
  (let [page (parse-page state)]
    (pagination/page-items (document-ids state)
                           page)))

(defn ^:private change-page-buttons [state owner _]
  (om/component
   (let [page (parse-page state)
         page-count (pagination/pages (document-ids state))
         navigation (:navigation state)
         route-fn (fn [page]
                    (-> navigation
                        (assoc-in [:query-params :page] page)
                        (nav/nav->route)))]
     (html
      [:nav.page
       [:a.button.prev {:class [(when (<= page 1)
                                  "disabled")]
                        :href (route-fn (dec page))}]
       [:a.button.next {:class [(when (>= page page-count)
                                  "disabled")]
                        :href (route-fn (inc page))}]]))))

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
  (let [documents (document-ids state)
        page (parse-page state)
        [start end] (pagination/page-range documents page)]
    (cond
     (search/search-query state)
     "Search Results"
    
     true
     "Dashboard ")))

(defn ^:private document-page-count-label [state _ _]
  (om/component
   (html
    (let [documents (document-ids state)
          page (parse-page state)
          [start end] (pagination/page-range documents page)]
      [:span.document-count
       (when (and start end)
         (str "(" start "-" end " of " (count documents) ")"))]))))

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
      {:sort/key :id
       :filter-width css/default-right-sidebar-width})
    om/IRenderState
    (render-state [_ local-state]
      ;; Show all documents with ids found in :dashboard/document-ids
      (let [document-ids (page-ids state)]
        (html
         [:.workflow.dashboard
          [:.pane {:style {:width (str "calc(100% - " (:filter-width local-state) "px - 2px)")}}
           [:header
            (dashboard-title state)
            (om/build document-page-count-label state)
            (om/build change-page-buttons state)]
           [:.documents
            (let [documents (->> document-ids
                                 (map (partial get (:documents state)))
                                 (remove nil?)
                                 (sort-by (:sort/key local-state)))]
              (om/build-all document-preview documents
                            {:key :id}))]]
          [:.pane {:style {:width (:filter-width local-state)}}
           (om/build filter-sidebar state)]])))))
