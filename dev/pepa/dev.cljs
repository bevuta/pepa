(ns ^:figwheel-always pepa.dev
    (:require [figwheel.client :as fw]))

;;; Watch for changes with Figheel
(fw/watch-and-reload
 :websocket-url   "ws://localhost:3449/figwheel-ws"
 :jsload-callback (fn [] (println "reloaded")))

