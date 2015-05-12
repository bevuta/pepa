(ns pepa.web
  (:require [pepa.web.handlers :refer [make-handlers]]
            [pepa.log :as log]

            [com.stuartsierra.component :as component]
            [immutant.web :as http-server]))

(defrecord Web [config db processor server]
  component/Lifecycle
  (start [component]
    (log/info component "Starting HTTP Server")
    (let [{:keys [host port static-handlers]} (:web config)
          handlers (if static-handlers
                     (make-handlers component)
                     #((make-handlers component) %))]
      (log/info component (str "Started web server on http://" host ":" port "/"))
      (assoc component
             :server (http-server/run handlers
                       :host host
                       :port port))))
  (stop [component]
    (log/info component "Stopping HTTP Server")
    (http-server/stop server)
    (assoc component :server nil)))

(defn make-component []
  (map->Web {}))
