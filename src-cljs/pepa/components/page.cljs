(ns pepa.components.page
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom]
            [pepa.data :as data]
            [pepa.api :as api]
            [sablono.core :refer-macros [html]]))

(defn ^:private page-image [page owner {:keys [size]}]
  (assert size)
  (om/component
   (dom/img #js {:src (str "/pages/" (:id page) "/image/" size)
                 :style (when-let [r (:rotation page)]
                          #js {:transform (str "rotate(" r "deg)")})})))

(defn ^:private rotate-clicked [page rotation e]
  (println "rotating" page "by" rotation "degrees")
  (api/rotate-page! page rotation)
  (doto e
    (.preventDefault)
    (.stopPropagation)))

(defn ^:private rotate-buttons [page owner]
  (om/component
   (let [rotation (or (:rotation page) 0)]
     (html
      [:.rotate
       [:.right {:on-click (partial rotate-clicked page (+ rotation 90))}
        "R"]
       [:.left  {:on-click (partial rotate-clicked page (- rotation 90))}
        "L"]]))))

(defn thumbnail [page owner {:keys [enable-rotate?]}]
  (reify
    om/IRenderState
    (render-state [_ {:keys [overlay?]}]
      (if enable-rotate?
        (dom/div #js {:className "thumbnail"
                      :onMouseEnter (fn [e] (om/set-state! owner :overlay? true))
                      :onMouseLeave (fn [e] (om/set-state! owner :overlay? false))}
                 (when overlay?
                   (om/build rotate-buttons page))
                 (om/build page-image page {:opts {:size "thumbnail"}}))
        (om/build page-image page {:opts {:size "thumbnail"}})))))

(defn full [page owner _]
  (page-image page owner {:size "full"}))
