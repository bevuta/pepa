(ns pepa.printing
  (:require [com.stuartsierra.component :as component]
            [lpd.server :as lpd]
            [lpd.protocol :as lpd-protocol])
  (:import [javax.jmdns JmDNS ServiceInfo]))

(defn ^:private job-handler [config]
  (reify
    lpd-protocol/IPrintJobHandler
    (accept-job [_ queue job]
      (println "got job on queue" queue)
      (prn job))))

(defn ^:private lpd-server [config]
  (lpd/make-server (assoc (select-keys config [:host :port])
                          :handler (job-handler config))))

(defn ^:private service-info [config]
  (ServiceInfo/create "_printer._tcp.local."
                      "LPD Server"
                      (:port config)
                      10
                      10
                      {"pdl" "application/pdf,application/postscript"
                       "rq" "some-queue"}))

(defrecord LPDPrinter [config mdns server]
  component/Lifecycle
  (start [component]
    (let [lpd-config (get-in config [:printing :lpd])]
      (if (:enable lpd-config)
        (do
          (println ";; Starting LPD Server")
          (let [server (-> (lpd-server lpd-config)
                           (lpd/start-server))
                jmdns (JmDNS/create)]
            (.registerService jmdns (service-info lpd-config))
            (assoc component
                   :mdns jmdns
                   :server server)))
        component)))
  (stop [component]
    (lpd/stop-server (:server component))
    (.close (:mdns component))
    (assoc component
           :mdns nil
           :server nil)))

(defn make-lpd-component []
  (map->LPDPrinter {}))
