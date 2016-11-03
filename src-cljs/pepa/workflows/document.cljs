(ns pepa.workflows.document
  (:require [om.core :as om :include-macros true]
            [cljs.core.async :as async :refer [alts! <!]]
            [clojure.string :as s]

            [pepa.api :as api]
            [pepa.model :as model]
            [pepa.navigation :as nav]
            [pepa.style :as css]
            [nom.ui :as ui]

            [pepa.components.utils :as utils]
            [pepa.components.page :as page]
            [pepa.components.tags :as tags]
            [pepa.components.document :as document]
            [pepa.components.draggable :as draggable]
            [pepa.components.editable :as editable])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]])
  (:import [goog.i18n DateTimeFormat DateTimeParse]))

;;; Editable Title Header

(defn ^:private save-title! [document owner title]
  (go
    (try
      (om/set-state! owner :working? true)
      (<! (api/update-document! (assoc @document :title title)))
      (finally
        (om/set-state! owner :working? false)))))

(ui/defcomponent ^:private thumbnail-pane-header [document owner _]
  (render [_]
    [:header
     (editable/editable-title (:title document)
                              (partial save-title! document owner))]))

(defn ^:private scroll-maybe [page owner prev-state]
  (when (and (= page (om/get-state owner :current-page))
             ;; Don't scroll if we have the same ID
             (not= (:id page) (:id (:current-page prev-state))))
    (let [el (om/get-node owner)]
      (.scrollIntoView el))))

(ui/defcomponent ^:private page-cell [page owner opts]
  (did-mount [_]
    (scroll-maybe (om/value page) owner nil))
  (did-update [_ _ prev-state]
    (scroll-maybe (om/value page) owner prev-state))
  (render-state [_ {:keys [current-page events]}]
    (assert (:view opts))
    [:li {:class [(when (= page current-page) "current")]
          :on-click (fn [e]
                      (async/put! events (om/value page))
                      (.preventDefault e))}
     (om/build (:view opts) page {:opts opts})]))

(ui/defcomponent ^:private thumbnail-list [pages owner]
  (render-state [_ {:keys [events current-page]}]
    [:ul.pages {:tab-index 0
                :on-key-down (fn [e]
                               (when-let [key (#{38 40} (.-keyCode e))]
                                 (case key
                                   38 (async/put! events :prev)
                                   40 (async/put! events :next))
                                 (.preventDefault e)))}
     (om/build-all page-cell pages
                   {:key :id
                    :init-state {:events events}
                    :state {:current-page current-page}
                    :opts {:enable-rotate? true
                           :view page/thumbnail}})]))

(ui/defcomponent ^:private thumbnails [document owner]
  (render-state [_ state]
    [:.thumbnails
     (om/build thumbnail-pane-header document)
     (om/build draggable/resize-draggable nil
               {:opts {:sidebar ::thumbnails}})
     (om/build thumbnail-list (:pages document)
               ;; Just pass down the whole state
               {:state state})]))

(ui/defcomponent ^:private pages-pane-header [document]
  (render [_]
    [:header
     [:label.dropdown
      "Show"
      [:select {:disabled true}
       [:option "PDF"]
       [:option "Plain Text"]]]]))

(ui/defcomponent ^:private pages-list [pages owner]
  (render-state [_ {:keys [events current-page]}]
    [:ul.pages {:tab-index 0}
     (om/build-all page-cell pages
                   {:key :id
                    :init-state {:events events}
                    :state {:current-page current-page}
                    :opts {:view page/full}})]))

(ui/defcomponent ^:private pages [document owner]
  (render-state [_ state]
    [:.full
     (om/build pages-pane-header document)
     (om/build pages-list (:pages document)
               {:state state})]))

(ui/defcomponent ^:private meta-pane [{:keys [document-date] :as document} owner]
  (render [_]
    [:.sidebar
     [:header "Meta"]
     (om/build draggable/resize-draggable nil
               {:opts {:sidebar ::meta}})
     ;; TODO: handle channels to set document tags
     (om/build document/document-sidebar [document])]))

(defn ^:private next-page [pages page]
  (->> pages
       (drop-while #(not= % page))
       (second)))

(defn ^:private prev-page [pages page]
  (next-page (reverse pages) page))

(defn ^:private page-idx [pages page]
  (some (fn [[idx p]] (when (= page p) idx))
        (map-indexed vector pages)))

(defn ^:private handle-page-click! [document owner event]
  (let [pages (:pages document)
        page (om/get-state owner :page-number)
        page (case event
               :next (min (inc page) (count pages))
               :prev (max (dec page) 1)
               (inc (page-idx pages event)))]
    (-> (nav/document-route (:id document) {:page page})
        (nav/navigate! :ignore-history))))

(ui/defcomponent document [document owner]
  (init-state [_]
    {:page-number 1
     :pages (async/chan)
     :tag-changes (async/chan)
     :date-changes (async/chan)})
  (will-mount [_]
    ;; Start handling tag change events etc.
    (go-loop []
      (let [{:keys [pages tag-changes date-changes]} (om/get-state owner)
            [event port] (alts! [pages tag-changes date-changes])]
        (when (and event port)
          (condp = port
            pages
            (handle-page-click! @document owner event)

            tag-changes
            (let [[op tag] event]
              ;; Deref to get the 'actual' value (might be stale)
              (-> @document
                  (update-in [:tags] (case op
                                       :add model/add-tags
                                       :remove model/remove-tags)
                             [tag])
                  (api/update-document!)))

            date-changes
            (let [date (js/Date.)]
              (.parse (DateTimeParse. "yyyy-MM-dd") event date)
              (-> @document
                  (assoc :document-date date)
                  (api/update-document!))))
          (recur)))))
  (render-state [_ state]
    (let [{:keys [page-number]} state
          page-events (:pages state)
          tag-changes (:tag-changes state)
          date-changes (:date-changes state)
          ;; TODO/rewrite
          ;; sidebars (om/observe owner (model/ui-sidebars))
          current-page (let [pages (:pages (om/value document))
                             pagenum (dec (or page-number 1))]
                         (when (< pagenum (count pages))
                           (nth pages pagenum)))]
      [:.workflow.document
       ;; Left (thunbmail) pane
       (let [width css/default-sidebar-width
             ;; TODO/rewrite
             #_(get sidebars ::thumbnails
                    css/default-sidebar-width)]
         [:.pane {:style {:min-width width :max-width width}
                  :key "thumbnail-pane"}
          (om/build thumbnails document
                    {:init-state {:events page-events}
                     :state {:current-page current-page}})])
       [:.pane {:key "pages-pane"}
        (om/build pages document
                  {:init-state {:events page-events}
                   :state {:current-page current-page}})]
       ;; Right (meta) pane
       (let [width css/default-sidebar-width
             ;; TODO/rewrite
             #_(get sidebars ::meta
                    css/default-sidebar-width)]
         [:.pane {:style {:min-width width :max-width width}
                  :key "meta-pane"}
          (om/build meta-pane document
                    {:init-state {:tags tag-changes
                                  :date date-changes}})])])))

;; TODO/rewrite
(defmethod draggable/pos->width ::thumbnails [sidebars sidebar [x _]]
  (draggable/limit
   (- x css/default-sidebar-width
      #_(get sidebars :pepa.components.sidebar/sidebar
             css/default-sidebar-width))
   100 400))

(defmethod draggable/pos->width ::meta [_ sidebar [x _]]
  (draggable/limit
   (- (draggable/viewport-width) x)))
