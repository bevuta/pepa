
(ns pepa.web
  (:require [pepa.web.handlers :refer [make-handlers]]
            [pepa.log :as log]
            [pepa.zeroconf :as zeroconf]

            [com.stuartsierra.component :as component]
            [immutant.web :as http-server]))

(defrecord Web [config db processor server]
  component/Lifecycle
  (start [component]
    (log/info "Starting HTTP Server")
    (let [{:keys [host port static-handlers]} (:web config)
          handlers (if static-handlers
                     (make-handlers component)
                     #((make-handlers component) %))]
      (log/info (str "Started web server on http://" host ":" port "/"))
      (assoc component
             :server (http-server/run handlers
                       :host host
                       :port port))))
  (stop [component]
    (log/info "Stopping HTTP Server")
    (http-server/stop server)
    (assoc component :server nil)))

(defn make-component []
  (map->Web {}))

;;; Zeroconf Service Implementation

(defmethod zeroconf/service-info :web [module config]
  (let [name "Pepa DMS"
        port (get-in config [:web :port])]
    (assert (< 0 port 65535))
    {:type "_http._tcp.local."
     :name name
     :port port
     :props {}}))
