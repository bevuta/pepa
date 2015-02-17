(ns pepa.core
  (:require pepa.style
            pepa.navigation

            [pepa.data :as data]
            [pepa.preloader :as preloader]
            [pepa.components.root :refer [root-component]]
            [pepa.components.draggable :as draggable]

            [om.core :as om :include-macros true]

            [weasel.repl :as ws-repl]))

(enable-console-print!)

(when-not (ws-repl/alive?)
  (ws-repl/connect (str "ws://" js/window.location.hostname ":9009")
                   :verbose true
                   :print :console))

;;; Preload Images
(preloader/preload)

(om/root root-component
         data/state
         {:target (.getElementById js/window.document "app")
          :shared (merge {}
                         (draggable/shared-data))})
