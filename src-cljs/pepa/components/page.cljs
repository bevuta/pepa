(ns pepa.components.page
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom]

            [pepa.ui :as ui]
            [pepa.data :as data]
            [pepa.api :as api]
            
            [sablono.core :refer-macros [html]]
            [goog.style :as style]))

(defn ^:private rotate [deg]
  (let [t (str "rotate(" deg "deg)")]
    #js {:WebkitTransform t
         :MozTransform t
         :OTransform t
         :MsTransform t
         :transform t}))

(def +pending-placeholder+ "/img/pending.svg")

(defn ^:private page-image [page owner {:keys [size size-fn]}]
  (assert (or size (fn? size-fn)))
  (om/component
   (let [rendered? (= :processing-status/processed (:render-status page))
         size (if (fn? size-fn)
                (apply size-fn (:dpi page))
                size)]
     (dom/img #js {:src (if rendered?
                          (str "/pages/" (:id page) "/image/" size)
                          +pending-placeholder+)
                   :style (rotate (:rotation page))
                   :className (when-not rendered?
                                "pending")}))))

(def +min-rotate-width+ 100)

(defn ^:private rotate-clicked [page rotation e]
  (println "rotating" page "to" rotation "degrees")
  (api/rotate-page! page rotation)
  (doto e
    (.preventDefault)
    (.stopPropagation)))

(ui/defcomponent ^:private rotate-buttons [page owner]
  (render [_]
    (let [rotation (or (:rotation page) 0)]
      [:.rotate
       [:.right {:on-click (partial rotate-clicked page (+ rotation 90))
                 :title "Rotate Clockwise"}]
       [:.left  {:on-click (partial rotate-clicked page (- rotation 90))
                 :title "Rotate Counterclockwise"}]])))

(ui/defcomponent thumbnail [page owner {:keys [enable-rotate?]}]
  (render-state [_ {:keys [overlay?]}]
    (if (and enable-rotate?
             (= :processing-status/processed (:render-status page)))
      (dom/div #js {:className "thumbnail"
                    :onMouseEnter (fn [e]
                                    (when (>= (.-width (style/getSize (om/get-node owner)))
                                              +min-rotate-width+)
                                      (om/set-state! owner :overlay? true)))
                    :onMouseLeave (fn [e] (om/set-state! owner :overlay? false))}
               (when overlay?
                 (om/build rotate-buttons page))
               (om/build page-image page {:opts {:size-fn min}}))
      (om/build page-image page {:opts {:size-fn min}}))))

(defn full [page owner _]
  (page-image page owner {:size-fn max}))
