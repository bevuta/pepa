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

(defn- document-component [state navigation]
  (let [{:keys [id page]} (:route-params navigation)
        id   (try (js/parseInt id 10) (catch js/Error e nil))
        page (try (js/parseInt id 10) (catch js/Error e nil))]
    (if-let [d (get-in state [:documents id])]
      (om/build document/document d
                {:state {:page-number (or page 1)}})
      (js/console.warn "Couldn't find document with id" id))))

(ui/defcomponent root-component [state owner]
  om/ICheckState
  (render-state [_ {:keys [file-drop?]}]
    (let [state (om/value state)        ;TODO/refactor: HACK
          nav (:navigation state)]
      (prn "state.navigation:" nav)
      [:div.container {:on-drag-over (partial root-drag-over state owner)
                       :on-drag-leave (partial root-drag-leave state owner)
                       :on-drop (partial root-drop state owner)
                       :class [(when file-drop? "file-drop")]}
       (om/build sidebar-component state
                 {:react-key "sidebar"})
       [:main {:key "main"}
        (match [(om/value (:handler nav))]
          [:dashboard]     (om/build dashboard/dashboard state)
          [[:search _]]    (om/build dashboard/dashboard state)
          [:inbox]         (om/build inbox2/inbox state)
          [:document]      (document-component state nav)
          [:document-page] (document-component state nav) 
          :else            (js/console.warn "unmatched route" (pr-str nav)))]
       (when-let [up (:upload state)]
         (om/build upload-dialog up
                   {:init-state {:mini? true}
                    :react-key "upload-dialog"}))])))
