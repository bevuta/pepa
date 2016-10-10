(ns pepa.components.sidebar
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [clojure.string :as s]
            [cljs.core.async :as async]

            [nom.ui :as ui]
            [pepa.model :as model]
            [pepa.navigation :as nav]
            [pepa.api.upload :as upload]
            [pepa.components.logo :refer [logo]]
            [pepa.components.tags :as tags]
            [pepa.components.draggable :refer [resize-draggable]]
            [pepa.style :as css]

            [pepa.search :refer [query-string]]
            [pepa.search.parser :as parser])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(ui/defcomponent ^:private search-field [_ owner opts]
  (init-state [_]
    {:query nil})
  (render-state [_ {:keys [query]}]
    [:form.search {:on-submit (fn [e]
                                (-> (if (seq query)
                                      (nav/full-search query)
                                      (nav/dashboard-route))
                                    (nav/navigate!))
                                (.preventDefault e))
                   ;; TODO: Search on tag-drop & document-drop (by id?)
                   ;; :on-drop #(js/console.log %)
                   }
     [:input {:value (or query "")
              :placeholder "Search"
              :on-change (fn [e]
                           (om/set-state! owner :query
                                          e.currentTarget.value)
                           true)}]]))

(defmulti ^:private navigation-element (fn [_ _ [name id _ route]] id))

(ui/defcomponentmethod navigation-element :default [state _ [title id _ href]]
  (render [_]
    [:a.menu-link {:href href}
     [:.title title]]))

(defn ^:private inbox-drop! [state owner e]
  ;; NOTE: We need to handle all event-object interop BEFORE entering
  ;; a go-block
  (let [files (upload/data-transfer-files event.dataTransfer)]
    (go
      (try
        (om/set-state! owner :working? true)
        (doseq [f files]
          (let [blob (<! (upload/file->u8arr f))]
            (<! (upload/upload-to-inbox! blob))))

        ;; TODO(mu): Remove this when the UI is working fine
        (<! (async/timeout 1000))
        (finally
          (doto owner
            (om/set-state! :working? false)
            (om/set-state! :drop? false))))))
  (.preventDefault e))

(ui/defcomponentmethod navigation-element :inbox [state owner [title id _ href]]
  (render-state [_ {:keys [drop? working?]}]
    [:a.menu-link {:href href}
     [:.title {:class [(when working? "working")
                       (when drop? "drop-target")]
               :on-drag-over upload/accept-file-drag
               :on-drag-enter #(om/set-state! owner :drop? true)
               :on-drag-leave #(om/set-state! owner :drop? false)
               :on-drop (partial inbox-drop! state owner)}
      title
      (when-let [pages (-> state :workflows :inbox :documents :inbox :pages seq)]
        [:span.count (count pages)])]]))

(def +tag-limit+ 5)

(ui/defcomponentmethod navigation-element :tags [state owner [title id _ href]]
  (init-state [_]
    {:show-all? false})
  (render-state [_ {:keys [show-all?]}]
    [:.menu-link
     [:.title title]
     (let [tags (sort-by val > (model/tag-count-map state true))
           ;; tags (if-not show-all? (take +tag-limit+ tags) tags)
           ]
       (om/build tags/tags-list tags))
     ;; [:.show-more {:on-click (fn [e]
     ;;                           (om/update-state! owner :show-all? not)
     ;;                           (.preventDefault e))}
     ;;  (if show-all?
     ;;    "Less ▲"
     ;;    "More ▼")]
     ]))

(defn ^:private route-matches? [route workflows]
  (let [route (if (seqable? route)
                (first route)
                route)]
    (or (= route workflows)
        (and (set? workflows) (contains? workflows route))
        (and (fn? workflows) (workflows route)))))

(ui/defcomponent sidebar-component [state owner opts]
  (render [_]
    (let [route (om/value (get-in state [:navigation :route]))
          width (get (om/observe owner (model/ui-sidebars)) ::sidebar
                     css/default-sidebar-width)]
      [:#sidebar {:style {:min-width width :max-width width}}
       (om/build resize-draggable nil
                 {:opts {:sidebar ::sidebar}
                  :react-key "draggable"})
       (om/build logo nil {:react-key "logo"})

       (om/build search-field nil
                 {:state {:query (query-string state)}
                  :react-key "search-field"})
       [:nav.workflows {:key "workflows"}
        [:ul
         ;; TODO: Use build-all
         (for [element nav/navigation-elements]
           (let [[title ident routes href] element
                 ident (name ident)]
             [:li {:class [ident
                           (when (route-matches? route routes) "active")]
                   :key ident}
              (om/build navigation-element state {:opts element
                                                  :react-key ident})]))]]])))
