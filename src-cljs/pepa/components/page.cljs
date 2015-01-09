(ns pepa.components.page
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom]
            [pepa.data :as data]
            [sablono.core :refer-macros [html]]))

(defn thumbnail [page owner _]
  (om/component
   (dom/img #js {:src (str "/pages/" (:id page) "/image/thumbnail")
                 :style (when-let [r (:rotation page)]
                          #js {:transform (str "rotate(" r "deg)")})})))

(defn full [page owner _]
  (om/component
   (dom/img #js {:src (str "/pages/" (:id page) "/image")
                 :style (when-let [r (:rotation page)]
                          #js {:transform (str "rotate(" r "deg)")})})))
