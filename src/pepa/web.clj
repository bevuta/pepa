(ns pepa.web
  (:require [com.stuartsierra.component :as component]
            [pepa.web.handlers :refer [make-handlers]]
            [immutant.web :as http-server]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]))

(defrecord Web [config db processor web-push server]
  component/Lifecycle
  (start [component]
    (println ";; Starting web server")
    (let [config (:web config)
          {:keys [host port]} config
          handlers (if (:static-handlers config)
                     (make-handlers component)
                     #((make-handlers component) %))
          handlers (if (:show-traces config)
                     (wrap-stacktrace handlers)
                     handlers)]
      (println (str ";; Started web server on http://" host ":" port "/"))
      (assoc component
        :server (http-server/run handlers
                  :host host
                  :port port))))

  (stop [component]
    (println ";; Stopping web server")
    (http-server/stop server)
    (assoc component :server nil)))

(defn make-component []
  (map->Web {}))
