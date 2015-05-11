(ns pepa.printing
  (:require [com.stuartsierra.component :as component]
            [lpd.server :as lpd]
            [lpd.protocol :as lpd-protocol]
            [pepa.log :as log])
  (:import [javax.jmdns JmDNS ServiceInfo]
           [java.net InetAddress]))

(defn ^:private job-handler [config]
  (reify
    lpd-protocol/IPrintJobHandler
    (accept-job [_ queue job]
      (println "got job on queue" queue)
      (prn job))))

(defn ^:private lpd-server [config]
  (lpd/make-server (assoc (select-keys config [:host :port])
                          :handler (job-handler config))))

(defn ^:private service-infos [config]
  (let [name "Pepa DMS Printer"
        queues (or (:queues config) ["auto"])]
    (for [queue queues]
      (ServiceInfo/create "_printer._tcp.local."
                          name
                          (:port config)
                          10
                          10
                          true
                          {"pdl" "application/pdf,application/postscript"
                           "rp" queue
                           "txtvers" "1"
                           "qtotal" (str (count queues))
                           "ty" name}))))

(defrecord LPDPrinter [config mdns server]
  component/Lifecycle
  (start [lpd]
    (let [lpd-config (get-in config [:printing :lpd])]
      (if (:enable lpd-config)
        (do
          (assert (< 0 (:port lpd-config) 65535))
          (log/info lpd "Starting LPD Server")
          (let [server (-> (lpd-server lpd-config)
                           (lpd/start-server))
                ;; TODO: Use IP from config?
                ip (InetAddress/getByName "moritz-x230")
                jmdns (JmDNS/create ip nil)]
            (doseq [service (service-infos lpd-config)]
              (log/info lpd "Registering service:" (str service))
              (.registerService jmdns service))
            (assoc lpd
                   :mdns jmdns
                   :server server)))
        lpd)))
  (stop [lpd]
    (log/info lpd "Stopping LPD Server")
    (when-let [server (:server lpd)]
      (lpd/stop-server server))
    (when-let [mdns (:mdns lpd)]
      (.close mdns))
    (assoc lpd
           :mdns nil
           :server nil)))

(defn make-lpd-component []
  (map->LPDPrinter {}))
