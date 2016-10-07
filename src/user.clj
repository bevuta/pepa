(ns user
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :refer [refresh]]
            [pepa.core :as pepa]
            [pepa.log :as log]

            [figwheel-sidecar.repl-api :as fig]))

(defonce system nil)

(defn init []
  (alter-var-root #'system
                  (constantly (pepa/make-system)))
  nil)

(defn start []
  (alter-var-root #'system component/start)
  nil)

(defn stop []
  (alter-var-root #'system
                  (fn [s]
                    (when s
                      (log/info "======== STOPPING SYSTEM ========")
                      (component/stop s))))
  nil)

(defn go []
  (init)
  (start))

(defn reload []
  (stop)
  (refresh :after 'user/go)
  nil)

;; Cljs REPL

(def start-figwheel! fig/start-figwheel!)
(def stop-figwheel!  fig/stop-figwheel!)
(def cljs-repl!      fig/cljs-repl)

(defn set-logback-level! [level]
  (doto (org.slf4j.LoggerFactory/getLogger ch.qos.logback.classic.Logger/ROOT_LOGGER_NAME)
    (.setLevel (ch.qos.logback.classic.Level/toLevel (name level)))))
