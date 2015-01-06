(ns pepa.workflows.document
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [cljs.core.async :as async :refer [alts! <!]]

            [pepa.navigation :as nav]
            [clojure.string :as s]

            [pepa.components.utils :as utils]
            [pepa.style :as css]

            [pepa.api :as api]
            [pepa.components.page :as page]
            [pepa.components.tags :as tags]
            [pepa.components.document :as document]
            [goog.events.KeyCodes :as keycodes])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]]))

(defn ^:private scroll-to-page! [owner page]
  (let [ref (str (:id page))
        page-el (om/get-node owner ref)]
    (.scrollIntoView page-el)))

;;; Editable Title Header

(defn ^:private save-title! [document owner e]
  (.preventDefault e)
  (go
    (try
      (om/set-state! owner :working? true)
      (<! (api/update-document! (assoc @document
                                       :title (om/get-state owner :title))))
      (om/set-state! owner :editing? false)
      (finally
        (om/set-state! owner :working? false)))))

(defn ^:private thumbnail-pane-header [document owner _]
  (reify
    om/IInitState
    (init-state [_]
      {:title (om/value (:title document))
       :editing? false})
    om/IDidUpdate
    (did-update [_ _ prev-state]
      (utils/focus-if owner prev-state :editing? "input" :move-caret))
    om/IRenderState
    (render-state [_ {:keys [title editing?]}]
      (let [save-title! (partial save-title! document owner)]
        (html
         [:header {:on-click (when-not editing?
                               #(doto owner
                                  (om/update-state! :editing? not)
                                  (om/set-state! :title (-> document :title om/value))))
                   :title (:title document)}
          (if editing?
            (list
             [:form {:on-submit save-title!}
              [:input.title {:value title
                             :ref "input"
                             :on-click #(.stopPropagation %)
                             :on-key-down (fn [e]
                                            (when (= keycodes/ESC e.keyCode)
                                              (om/set-state! owner :editing? false)))
                             :on-change (fn [e]
                                          (om/set-state! owner :title e.currentTarget.value))}]
              [:button.save {:type :submit
                             :on-click #(.stopPropagation %)}
               "Save"]])
            (:title document))])))))

(defn ^:private show-current-page!
  "Scrolls to :current-page of OWNER iff it's different
  to :current-page in PREV-STATE."
  [owner prev-state]
  (let [page (om/get-state owner :current-page)]
    (when (not= page (:current-page prev-state))
      (scroll-to-page! owner page))))

(defn ^:private thumbnails [pages owner]
  (reify
    om/IDidUpdate
    (did-update [_ _ prev-state]
      (show-current-page! owner prev-state))
    om/IRenderState
    (render-state [_ {:keys [events current-page]}]
      (html
       [:ul.pages {:tab-index 0
                   :on-key-down (fn [e]
                                  (when-let [key (#{38 40} (.-keyCode e))]
                                    (case key
                                      38 (async/put! events :prev)
                                      40 (async/put! events :next))
                                    (.preventDefault e)))}
        (for [page pages]
          [:li {:class [(when (= page current-page) "current")]
                :ref (str (:id page))
                :key (:id page)
                :on-click (fn [e]
                            (async/put! events (om/value page))
                            (.preventDefault e))}
           (om/build page/thumbnail page)])]))))

(defn ^:private pages-pane-header [document]
  (om/component
   (html
    [:header
     [:label.dropdown
      "Show"
      [:select {:disabled true}
       [:option "PDF"]
       [:option "Plain Text"]]]])))

(defn ^:private pages [pages owner]
  (reify
    om/IDidUpdate
    (did-update [_ _ prev-state]
      (show-current-page! owner prev-state))
    om/IRenderState
    (render-state [_ {:keys [events current-page]}]
      (html
       [:ul.pages {:tab-index 0}
        (for [page pages]
          [:li {:class [(when (= page current-page) "current")]
                :ref (str (:id page))
                :key (:id page)}
           (om/build page/full page {:key :id})])]))))

(defn ^:private meta-pane [document owner]
  (om/component
   (html
    [:.sidebar
     [:header "Meta"]
     [:aside
      (om/build document/meta-table document)
      ;; Input Fields: Tags, Sharing, Notes, etc.
      [:.fields
       (om/build tags/tags-input document
                 {:state (om/get-state owner)})]

      ;; Buttons: Download, Delete, etc.
      [:.buttons
       [:button.download {:on-click #(js/window.open
                                      (str "/documents/" (:id document) "/download"))}
        "Download"]
       [:button.delete {:disabled true}
        "Nukular vernichten"]]]])))

(defn ^:private next-page [pages page]
  (->> pages 
       (drop-while #(not= % page))
       (second)))

(defn ^:private prev-page [pages page]
  (next-page (reverse pages) page))

(defn ^:private handle-page-click! [document owner event]
  (let [pages (om/value (:pages document))]
    (om/update-state! owner :current-page
                      (fn [page] 
                        (or
                         (case event
                           :next (next-page pages page)
                           :prev (prev-page pages page)
                           event)
                         ;; If there's no new page, return the current
                         ;; page
                         page)))))

(defn document [document owner]
  (reify
    om/IInitState
    (init-state [_]
      {:current-page (first (:pages document))
       :thumbnail-width 300
       :sidebar-width css/default-right-sidebar-width
       :sidebar/visible? true
       :pages (async/chan)
       :tag-changes (async/chan)})
    om/IWillMount
    (will-mount [_]
      ;; Refresh document from server
      (api/fetch-documents! [(-> document om/value :id)])
      ;; Start handling tag change events etc.
      (go-loop []
        (let [pages (om/get-state owner :pages)
              tag-changes (om/get-state owner :tag-changes)
              [event port] (alts! [pages tag-changes])]
          (when (and event port)
            (cond
              (= port pages)
              (handle-page-click! document owner event)
              
              (= port tag-changes)
              (let [[op tag] event]
                ;; Deref to get the 'actual' value (might be stale)
                (-> @document
                    (update-in [:tags] (case op
                                         :add conj
                                         :remove disj)
                               tag)
                    (api/update-document!))))
            (recur)))))
    om/IRenderState
    (render-state [_ state]
      (let [{:keys [current-page thumbnail-width sidebar-width]} state
            page-events (:pages state)
            tag-changes (:tag-changes state)
            sidebar-width (if (:sidebar/visible? state)
                            sidebar-width
                            0)]
        (html
         [:.workflow.document
          [:.pane {:style {:width thumbnail-width}}
           [:.thumbnails
            (om/build thumbnail-pane-header document)
            (om/build thumbnails (:pages document)
                      {:init-state {:events page-events}
                       :state {:current-page current-page}})]]
          [:.pane {:style {:width (str "calc("
                                       "100%"
                                       " - " thumbnail-width "px"
                                       " - " sidebar-width "px"
                                       ")")}}
           [:.full
            (om/build pages-pane-header document)
            (om/build pages (:pages document)
                      {:state {:current-page current-page
                               :events page-events}})]]
          [:.pane {:style {:width sidebar-width}}
           (om/build meta-pane document
                     {:init-state {:tags tag-changes}})]])))))
