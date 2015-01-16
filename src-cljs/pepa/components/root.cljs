(ns pepa.components.root
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]

            [pepa.api :as api]
            [pepa.api.upload :as upload]
            [pepa.data :as data]
            [pepa.style :as css]
            [pepa.components.sidebar :refer [sidebar-component]]
            [pepa.components.draggable :as draggable]
            [pepa.workflows.inbox :as inbox]
            [pepa.workflows.dashboard :as dashboard]
            [pepa.workflows.document :as document]
            [pepa.workflows.upload
             :refer [upload-dialog]
             :as upload-list]
            [cljs.core.async :as async :refer [<! >!]]
            [cljs.core.match])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [cljs.core.match.macros :refer [match]]))

(defn ^:private fetch-initial-data!
  "Fetches initial data (all tags etc.) to initialize the
  application."
  [state route]
  (go
    ;; Inbox initial data
    (om/update! state :workflow/inbox inbox/initial-data)
    (<! (api/fetch-tags! state))))

(defn transition-to! [state route query-params]
  (println "route" (pr-str route)
           "query-params" (pr-str query-params))
  (match [route]
    [[:document id]] (api/fetch-documents! #{id})
    :else nil))

;;; Drag/Drop Handling

(defn ^:private root-drag-over [state owner e]
  (let [valid? (upload/drop-valid? e)]
    (om/set-state! owner :file-drop? valid?)
    (when valid?
      (.preventDefault e))))

(defn ^:private root-drag-leave [state owner e]
  (om/set-state! owner :file-drop? false))

(defn ^:private root-drop [state owner e]
  (try
    (doseq [file (upload/data-transfer-files e.dataTransfer)]
      (om/transact! state :upload
                    #(upload-list/add-file % file)))
    (finally
      (om/set-state! owner :file-drop? false)))
  (.preventDefault e))

(defn root-component [state owner]
  (reify
    om/ICheckState
    om/IInitState
    (init-state [_]
      {:sidebar-widths (async/chan (async/sliding-buffer 1))})
    om/IWillMount
    (will-mount [_]
      (let [{:keys [route query-params]} (:navigation state)]
        (fetch-initial-data! state route)
        (let [route (om/value route)]
          (transition-to! state route query-params))
        
        ;; Loop to handle resize-events for the sidebar
        (draggable/width-loop ::sidebar (om/get-state owner :sidebar-widths)
                              [css/min-sidebar-width css/max-sidebar-width])))
    om/IWillUpdate
    (will-update [_ next-props next-state]
      (when-not (= (get-in (om/get-props owner) [:navigation :route])
                   (get-in next-props [:navigation :route]))
        (let [{:keys [route query-params]} (om/value (:navigation next-props))]
          (transition-to! state route query-params))))
    om/IRenderState
    (render-state [_ {:keys [file-drop? sidebar-widths]}]
      (let [{:keys [route query-params]} (:navigation state)
            sidebar-width (or (::sidebar (om/observe owner (data/ui-sidebars)))
                              css/default-sidebar-width)]
        (html
         [:div#app {:on-drag-over (partial root-drag-over state owner)
                    :on-drag-leave (partial root-drag-leave state owner)
                    :on-drop (partial root-drop state owner)
                    :class [(when file-drop? "file-drop")]}
          (om/build sidebar-component state
                    {:state {:width sidebar-width}
                     :init-state {:widths sidebar-widths}})
          [:main {:style {:margin-left sidebar-width}}
           (match [(om/value route)]
             [:dashboard]  (om/build dashboard/dashboard state)
             [[:search _]] (om/build dashboard/dashboard state)
             [:inbox] (when-let [c (:workflow/inbox state)]
                        (om/build inbox/group-pages-workflow c))
             [[:document id]] (when-let [d (get-in state [:documents id])]
                                (let [page (some-> (:page query-params)
                                                   (js/parseInt 10)
                                                   (try (catch js/Error e nil)))
                                      page (if (integer? page) page 1)]
                                  (om/build document/document d
                                            {:state {:page-number page}})))
             :else (js/console.log "unmatched route" (pr-str route)))]
          (when-let [up (:upload state)]
            (om/build upload-dialog up
                      {:init-state {:mini? true}}))])))))
