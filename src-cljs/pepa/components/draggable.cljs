(ns pepa.components.draggable
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [cljs.core.async :as async]

            [clojure.browser.event :as bevent])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn ^:private resize-draggable-mouse-down [owner e]
  ;; Attach event handlers to window
  (let [on-move (fn [e]
                  (let [{:keys [dragging? positions]} (om/get-state owner)
                        pos [(.-clientX e) (.-clientY e)]]
                    (when (and dragging? positions)
                      (async/put! positions pos))))
        on-up (fn [e]
                (doseq [handler (om/get-state owner :handlers)]
                  (bevent/unlisten-by-key handler))
                (doto owner
                  (om/set-state! :dragging? false)
                  (om/set-state! :start nil)
                  (om/set-state! :handlers nil)))]
    (let [el js/window.document.documentElement
          handlers [(bevent/listen el "mousemove" on-move)
                    (bevent/listen el "mouseup" on-up)]]
      (doto owner
        (om/set-state! :dragging? true)
        (om/set-state! :handlers handlers))))
  (doto e
    (.preventDefault)
    (.stopPropagation)))

(defn resize-draggable [_ owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [dragging? positions
                             position-changed]}]
      (html
       [:.draggable {:class [(when dragging?
                               "active")]
                     :on-mouse-down (partial resize-draggable-mouse-down owner)}]))))
