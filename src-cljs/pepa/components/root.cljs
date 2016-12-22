(ns pepa.components.root
  (:require [om.core :as om :include-macros true]

            [nom.ui :as ui]

            [pepa.model :as model]
            [pepa.model.route :as route]

            [pepa.api :as api]
            [pepa.api.upload :as upload]
            [pepa.search :as search]
            [pepa.components.sidebar :refer [sidebar-component]]
            ;; [pepa.components.draggable :as draggable]
            [pepa.workflows.inbox2 :as inbox2]
            [pepa.workflows.dashboard :as dashboard]
            [pepa.workflows.document :as document]
            [pepa.workflows.upload :refer [upload-dialog] :as upload-list]

            [cljs.core.async :as async :refer [<! >!]]
            [cljs.core.match])
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop]]
   [cljs.core.match.macros :refer [match]]))

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

(defn- document-component [state]
  (let [{:keys [id page]} (::route/route-params state)]
    (if-let [d (get-in state [:documents id])]
      (om/build document/document d
                {:state {:page-number (or page 1)}})
      (js/console.warn "Couldn't find document with id" id))))

(ui/defcomponent root-component [state owner]
  om/ICheckState
  (render-state [_ {:keys [file-drop?]}]
    (let [state (om/value state)
          {::route/keys [handler route-params]} state]
      [:div.container {:on-drag-over (partial root-drag-over state owner)
                       :on-drag-leave (partial root-drag-leave state owner)
                       :on-drop (partial root-drop state owner)
                       :class [(when file-drop? "file-drop")]}
       (om/build sidebar-component state
                 {:react-key "sidebar"})
       [:main {:key "main"}
        (match [handler]
          [:dashboard]     (om/build dashboard/dashboard state)
          [[:search _]]    (om/build dashboard/dashboard state)
          [:inbox]         (om/build inbox2/inbox state)
          [:document]      (document-component state)
          [:document-page] (document-component state) 
          :else            (js/console.warn "Unmatched route" (pr-str handler)))]
       (when-let [up (:upload state)]
         (om/build upload-dialog up
                   {:init-state {:mini? true}
                    :react-key "upload-dialog"}))])))
