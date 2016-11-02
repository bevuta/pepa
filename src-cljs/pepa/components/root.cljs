(ns pepa.components.root
  (:require [om.core :as om :include-macros true]

            [nom.ui :as ui]
            [pepa.api :as api]
            [pepa.api.upload :as upload]
            [pepa.model :as model]
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

(ui/defcomponent root-component [state owner]
  om/ICheckState
  (render-state [_ {:keys [file-drop?]}]
    (let [{:keys [route query-params]} (:navigation state)]
      [:div.container {:on-drag-over (partial root-drag-over state owner)
                       :on-drag-leave (partial root-drag-leave state owner)
                       :on-drop (partial root-drop state owner)
                       :class [(when file-drop? "file-drop")]}
       (om/build sidebar-component state
                 {:react-key "sidebar"})
       [:main {:key "main"}
        (match [(om/value route)]
          [:dashboard]  (om/build dashboard/dashboard state)
          [[:search _]] (om/build dashboard/dashboard state)
          [:inbox]      (om/build inbox2/inbox state)
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
                   {:init-state {:mini? true}
                    :react-key "upload-dialog"}))])))
