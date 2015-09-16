(ns pepa.components.editable
  (:require [om.core :as om]
            [nom.ui :as ui]
            [pepa.components.utils :as utils]

            [goog.events.KeyCodes :as keycodes]))

(ui/defcomponent ^:private editable-title* [value owner]
  (init-state [_]
    {:editable? false})
  (did-update [_ _ prev-state]
    (utils/focus-if owner prev-state :editable? "input" :move-caret))
  (render-state [_ {:keys [editable? save-button? callback]}]
    [:.editable {:on-click #(when-not editable?
                              (om/set-state! owner :editable? true))
                 :title value}
     (if editable?
       [:form {:on-submit (fn [e]
                            (ui/cancel-event e)
                            (some->> (om/get-node owner "input")
                                     (.-value)
                                     (callback))
                            (om/set-state! owner :editable? false))
               :ref "form"}
        [:input {:default-value value
                 :key "input"
                 :ref "input"
                 :on-click #(.stopPropagation %)
                 :on-key-down (fn [e]
                                (when (= keycodes/ESC e.keyCode)
                                  (om/set-state! owner :editable? false)))
                 ;; When we run without save button, submit form on blur
                 :on-blur (when-not save-button?
                            (fn [e]
                              (.click (om/get-node owner "save-button"))))}]
        [:button.save {:type "submit"
                       :key "save-button"
                       :ref "save-button"
                       :style (when-not save-button? {"display" "none"})}
         "Save"]]
       value)]))

(defn editable-title
  ([props callback save-button?]
   {:pre [(fn? callback)]}
   (om/build editable-title* props
             {:state {:callback callback
                      :save-button? save-button?}}))
  ([props callback]
   (editable-title props callback true)))
