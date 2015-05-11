(ns pepa.printing
  (:require [com.stuartsierra.component :as component]
            [clojure.java.io :as io]
            
            [lpd.server :as lpd]
            [lpd.protocol :as lpd-protocol]

            [pepa.db :as db]
            [pepa.model :as model]
            [pepa.log :as log]
            [pepa.ghostscript :as gs]
            [pepa.util :as util])
  (:import [javax.jmdns JmDNS ServiceInfo]
           [java.net InetAddress]))

(defn ^:private job-handler [lpd]
  (reify
    lpd-protocol/IPrintJobHandler
    (accept-job [_ queue job]
      (log/info lpd "got job on queue" queue
                job)
      (db/with-transaction [db (:db lpd)]
        (let [name (or (:source-filename job)
                       (:banner-name job)
                       "Printed File")
              pdf (gs/ps->pdf (:data job))
              file-props {:content-type "application/pdf"
                          :name name
                          :origin (str "printer/" queue)
                          :data (util/slurp-bytes pdf)}
              file (model/store-file! db file-props)
              origin "printer"]
          (when-not false ; (model/inbox-origin? (:config lpd) origin)
            (log/info lpd "Creating Document for file" (:id file))
            (let [document (model/create-document! db {:title (:name file-props)
                                                       :file (:id file)})
                  tagging-config (get-in lpd [:config :tagging])]
              (log/info lpd "Auto-tagging document" document)
              (model/auto-tag! db document tagging-config
                               {:origin origin
                                :printing/queue (str "printer/" queue)}))))))))

(defn ^:private lpd-server [lpd config]
  (lpd/make-server (assoc (select-keys config [:host :port])
                          :handler (job-handler lpd))))

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
                           "ty" (str name " (Queue: " queue ")")}))))

(defrecord LPDPrinter [config db mdns server]
  component/Lifecycle
  (start [lpd]
    (let [lpd-config (get-in config [:printing :lpd])]
      (if (:enable lpd-config)
        (do
          (assert (< 0 (:port lpd-config) 65535))
          (log/info lpd "Starting LPD Server")
          (let [server (-> (lpd-server lpd lpd-config)
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
