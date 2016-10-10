(ns ^:figwheel-always pepa.core
  (:require pepa.style
            pepa.navigation

            [pepa.model :as model]
            [pepa.preloader :as preloader]
            [pepa.components.root :refer [root-component]]
            [pepa.components.draggable :as draggable]

            [om.core :as om :include-macros true]))

(enable-console-print!)

;;; Preload Images
(defonce preloaded-images (preloader/preload))

(om/root root-component
         model/state
         {:target (.getElementById js/window.document "app")
          :shared (merge {}
                         (draggable/shared-data))})

