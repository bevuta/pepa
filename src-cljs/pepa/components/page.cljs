(ns pepa.components.page
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom]
            [pepa.data :as data]
            [pepa.api :as api]
            [sablono.core :refer-macros [html]]))

(defn ^:private rotate [deg]
  (let [t (str "rotate(" deg "deg)")]
    #js {:WebkitTransform t
         :MozTransform t
         :OTransform t
         :MsTransform t
         :transform t}))

(def +pending-placeholder+ "/img/pending.svg")

(defn ^:private page-image [page owner {:keys [size]}]
  (assert size)
  (om/component
   (let [rendered? (= :processing-status/processed (:render-status page))]
     (dom/img #js {:src (if rendered?
                          (str "/pages/" (:id page) "/image/" size)
                          +pending-placeholder+)
                   :style (rotate (:rotation page))
                   :className (when-not rendered?
                                "pending")}))))

(defn ^:private rotate-clicked [page rotation e]
  (println "rotating" page "to" rotation "degrees")
  (api/rotate-page! page rotation)
  (doto e
    (.preventDefault)
    (.stopPropagation)))

(defn ^:private rotate-buttons [page owner]
  (om/component
   (let [rotation (or (:rotation page) 0)]
     (html
      [:.rotate
       [:.right {:on-click (partial rotate-clicked page (+ rotation 90))
                 :title "Rotate Clockwise"}]
       [:.flip.vertical {:on-click (partial rotate-clicked page (+ rotation 180))
                         :title "Flip Vertical"}]
       [:.left  {:on-click (partial rotate-clicked page (- rotation 90))
                 :title "Rotate Counterclockwise"}]]))))

(defn thumbnail [page owner {:keys [enable-rotate?]}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [overlay?]}]
      (if (and enable-rotate?
               (= :processing-status/processed (:render-status page)))
        (dom/div #js {:className "thumbnail"
                      :onMouseEnter (fn [e] (om/set-state! owner :overlay? true))
                      :onMouseLeave (fn [e] (om/set-state! owner :overlay? false))}
                 (when overlay?
                   (om/build rotate-buttons page))
                 (om/build page-image page {:opts {:size "thumbnail"}}))
        (om/build page-image page {:opts {:size "thumbnail"}})))))

(defn full [page owner _]
  (page-image page owner {:size "full"}))
