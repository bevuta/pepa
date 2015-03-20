(ns user
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.namespace.repl :refer [refresh]]
            [pepa.core :as pepa]
            [pepa.log :as log]))

(defonce system nil)

(defn init []
  (alter-var-root #'system
                  (constantly (pepa/make-system))))

(defn start []
  (alter-var-root #'system component/start))

(defn stop []
  (alter-var-root #'system
                  (fn [s]
                    (when s
                      (log/info s "======== STOPPING SYSTEM ========")
                      (component/stop s)))))

(defn go []
  (init)
  (start))

(defn reload []
  (stop)
  (refresh :after 'user/go))

;; Cljs REPL

(defn ws-repl []
  (require 'cemerick.piggieback
           'weasel.repl.websocket)
  (let [cljs-repl (ns-resolve 'cemerick.piggieback 'cljs-repl)
        repl-env (ns-resolve 'weasel.repl.websocket 'repl-env)]
    (cljs-repl
     :repl-env (repl-env
                :ip "0.0.0.0"
                :port 9009
                :working-dir "resources/public/out"))))

(defn set-logback-level! [level]
  (doto (org.slf4j.LoggerFactory/getLogger ch.qos.logback.classic.Logger/ROOT_LOGGER_NAME)
    (.setLevel (ch.qos.logback.classic.Level/toLevel (name level)))))
