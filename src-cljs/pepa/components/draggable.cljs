(ns pepa.components.draggable
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [cljs.core.async :as async]

            [clojure.browser.event :as bevent]
            [goog.dom :as gdom])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn ^:private resize-draggable-mouse-down [owner e]
  ;; Attach event handlers to window
  (let [on-move (fn [e]
                  (let [{:keys [dragging? positions xs ys]} (om/get-state owner)]
                    (when dragging?
                      (let [pos [(.-clientX e) (.-clientY e)]
                            [x y] pos]
                        (when positions (async/put! positions pos))
                        (when xs (async/put! xs x))
                        (when ys (async/put! ys y))))))
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

(def right-align-xform
  (map #(- (.-width (gdom/getViewportSize )) %)))

(defn resize-draggable [_ owner]
  (reify
    om/IRenderState
    (render-state [_ {:keys [dragging?]}]
      (html
       [:.draggable {:class [(when dragging?
                               "active")]
                     :on-mouse-down (partial resize-draggable-mouse-down owner)}]))))
