(ns pepa.workflows.document
  (:require [om.core :as om :include-macros true]
            [cljs.core.async :as async :refer [alts! <!]]
            [clojure.string :as s]

            [pepa.api :as api]
            [pepa.data :as data]
            [pepa.navigation :as nav]
            [pepa.style :as css]
            [nom.ui :as ui]

            [pepa.components.utils :as utils]
            [pepa.components.page :as page]
            [pepa.components.tags :as tags]
            [pepa.components.document :as document]
            [pepa.components.draggable :as draggable]
            [goog.events.KeyCodes :as keycodes])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]]))

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

(ui/defcomponent ^:private thumbnail-pane-header [document owner _]
  (init-state [_]
    {:title (om/value (:title document))
     :editing? false})
  (did-update [_ _ prev-state]
    (utils/focus-if owner prev-state :editing? "input" :move-caret))
  (render-state [_ {:keys [title editing?]}]
    (let [save-title! (partial save-title! document owner)]
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
         (:title document))])))

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

(ui/defcomponent ^:private meta-pane [document owner]
  (render [_]
    [:.sidebar
     [:header "Meta"]
     (om/build draggable/resize-draggable nil
               {:opts {:sidebar ::meta}})
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
        "Nukular vernichten"]]]]))

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
    (-> (nav/nav->route {:route [:document (:id document)]
                         :query-params {:page page}})
        (nav/navigate! :ignore-history))))

(ui/defcomponent document [document owner]
  (init-state [_]
    {:page-number 1
     :pages (async/chan)
     :tag-changes (async/chan)})
  (will-mount [_]
    ;; Start handling tag change events etc.
    (go-loop []
      (let [{:keys [pages tag-changes]} (om/get-state owner)
            [event port] (alts! [pages tag-changes])]
        (when (and event port)
          (condp = port
            pages
            (handle-page-click! @document owner event)
            
            tag-changes
            (let [[op tag] event]
              ;; Deref to get the 'actual' value (might be stale)
              (-> @document
                  (update-in [:tags] (case op
                                       :add data/add-tags
                                       :remove data/remove-tags)
                             [tag])
                  (api/update-document!))))
          (recur)))))
  (render-state [_ state] 
    (let [{:keys [page-number]} state
          page-events (:pages state)
          tag-changes (:tag-changes state)
          sidebars (om/observe owner (data/ui-sidebars))
          meta-width (get sidebars ::meta
                          css/default-sidebar-width)
          thumbnail-width (get sidebars ::thumbnails
                               css/default-sidebar-width)
          current-page (nth (:pages (om/value document))
                            (dec (or page-number 1)))]
      [:.workflow.document
       [:.pane {:style {:min-width thumbnail-width
                        :max-width thumbnail-width}}
        (om/build thumbnails document
                  {:init-state {:events page-events}
                   :state {:current-page current-page}})]
       [:.pane
        (om/build pages document
                  {:init-state {:events page-events}
                   :state {:current-page current-page}})]
       [:.pane {:style {:min-width meta-width
                        :max-width meta-width}}
        (om/build meta-pane document
                  {:init-state {:tags tag-changes}})]])))

(defmethod draggable/pos->width ::thumbnails [sidebars sidebar [x _]]
  (draggable/limit
   (- x (get sidebars :root/sidebar
             css/default-sidebar-width))
   100 400))

(defmethod draggable/pos->width ::meta [_ sidebar [x _]]
  (draggable/limit
   (- (draggable/viewport-width) x)))
