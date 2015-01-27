(ns pepa.components.draggable
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [cljs.core.async :as async]

            [pepa.data :as data]
            [pepa.style :as css]

            [clojure.browser.event :as bevent]
            [goog.dom :as gdom]
            [cljs.reader :refer [read-string]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:import goog.storage.Storage
           goog.storage.mechanism.HTML5LocalStorage))

(defn ^:private resize-mouse-down [owner sidebar e]
  ;; Only handle 'main' mouse button
  (when (zero? (.-button e))
    ;; Attach event handlers to window
    (let [on-move (fn [e]
                    (let [{:keys [dragging?]} (om/get-state owner)]
                      (when dragging?
                        (let [pos [(.-clientX e) (.-clientY e)]]
                          (when-let [positions (om/get-shared owner ::events)]
                            (async/put! positions [sidebar pos]))))))
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
      (.stopPropagation))))

(defn resize-draggable [_ owner {:keys [sidebar]}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [dragging?]}]
      (html
       [:.draggable {:class [(when dragging?
                               "active")]
                     :on-mouse-down (partial resize-mouse-down owner sidebar)}]))))

(defn limit
  ([width min max]
   (cond
     (< width min)
     min
     (> width max)
     max
     true
     width))
  ([width]
   (limit width css/min-sidebar-width css/max-sidebar-width)))

(defmulti pos->width (fn [sidebars sidebar [x y]] sidebar))
(defmethod pos->width :default [_ _ [x _]]
  (limit x))

(defn viewport-width []
  (.-width (gdom/getViewportSize)))

(defn shared-data []
  {::events (async/chan)})

(def ^:private +save-timeout+ (* 0.5 1000))

(let [storage (Storage. (HTML5LocalStorage.))]
  (defn ^:private save-sidebar-state! [sidebars]
    (println "saving sidebar state")
    (.set storage (str ::sidebars) (pr-str sidebars)))

  (defn ^:private read-sidebar-state []
    (println "saving sidebar state")
    (try
      (-> (.get storage (str ::sidebars))
          (read-string))
      (catch js/Error e
        (println "Couldn't read sidebar state from local storage.")))))

(defn resize-handle-loop [root-owner]
  ;; Read sizes from local storage
  (om/update! (data/ui-sidebars) (or (read-sidebar-state) {}))
  (go-loop [changed? false]
    (let [resizes (om/get-shared root-owner ::events)
          timeout (async/timeout +save-timeout+)
          [event port] (alts! [resizes timeout])]
      (cond
        (and event (= port resizes))
        (let [[sidebar pos] event]
          (om/transact! (data/ui-sidebars)
                        (fn [sidebars]
                          (assoc sidebars sidebar
                                 (pos->width sidebars sidebar pos))))
          (recur true))
        (= port timeout)
        (do
          (when changed? (save-sidebar-state! @(data/ui-sidebars)))
          (recur false))))))
