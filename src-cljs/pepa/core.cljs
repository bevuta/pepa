(ns ^:figwheel-always pepa.core
  (:require pepa.style
            pepa.navigation

            [pepa.data :as data]
            [pepa.preloader :as preloader]
            [pepa.components.root :refer [root-component]]
            [pepa.components.draggable :as draggable]

            [om.core :as om :include-macros true]

            [figwheel.client :as fw]))

(enable-console-print!)

;;; Watch for changes with Figheel
(fw/watch-and-reload
 :websocket-url   "ws://localhost:3449/figwheel-ws"
 :jsload-callback (fn [] (println "reloaded")))

;;; Preload Images
(defonce preloaded-images (preloader/preload))

(om/root root-component
         data/state
         {:target (.getElementById js/window.document "app")
          :shared (merge {}
                         (draggable/shared-data))})

