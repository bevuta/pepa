(ns ^:figwheel-always pepa.core
  (:require pepa.style
            pepa.navigation

            [pepa.controller :as controller]
            [pepa.model :as model]
            [pepa.preloader :as preloader]
            [pepa.components.root :refer [root-component]]
            [pepa.components.draggable :as draggable]

            [om.core :as om :include-macros true]))

(enable-console-print!)

;;; Preload Images
(defonce preloaded-images (preloader/preload))

(defonce controller (doto (controller/new-controller)
                      (controller/fetch-initial-data!)))


(om/root root-component
         (:state controller)
         {:target (.getElementById js/window.document "app")
          :shared (merge {}
                         (draggable/shared-data))})
