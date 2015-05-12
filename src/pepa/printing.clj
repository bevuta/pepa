(ns pepa.printing
  (:require [com.stuartsierra.component :as component]
            [clojure.java.io :as io]
            
            [lpd.server :as lpd]
            [lpd.protocol :as lpd-protocol]

            [pepa.db :as db]
            [pepa.model :as model]
            [pepa.log :as log]
            [pepa.util :as util])
  (:import [javax.jmdns JmDNS ServiceInfo]
           [java.net InetAddress]
           java.lang.ProcessBuilder
           java.io.File))

(defn ^:private pdf? [bytes]
  (= "%PDF" (String. bytes 0 4)))

(defn ^:private ghostscript? [bytes]
  (= "%!" (String. bytes 0 2)))

(defn ^:private ps->pdf [input]
  (let [tmp-file (File/createTempFile "ps2pdf-output" ".pdf")
        process (-> ["ps2pdf" "-" (str tmp-file)]
                    (ProcessBuilder.)
                    (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                    (.start))
        process-input (.getOutputStream process)]
    (io/copy (io/input-stream input) process-input)
    (.close process-input)
    (let [exit-code (.waitFor process)]
      (if (zero? exit-code)
        tmp-file
        (throw (ex-info "Failed to run subprocess to extract meta-data."
                        {:exit-code exit-code}))))))

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
              pdf (let [data (:data job)]
                    ;; Check magic bytes in the job data. 
                    (cond
                      (pdf? data) data
                      (ghostscript? data) (ps->pdf data)
                      true (do
                             (log/warn lpd "Couldn't determine file type of print job. Interpreting it as Postscript")
                             (ps->pdf data))))
              file-props {:content-type "application/pdf"
                          :name name
                          :origin (str "printer/" queue)
                          :data (util/slurp-bytes pdf)}
              file (model/store-file! db file-props)
              origin "printer"]
          ;; TODO: Allow printing to inbox
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

;;; TODO: Move service announcement to its own component
(defn ^:private service-infos [config]
  ;; TODO: Think about a better way to configure the announced name
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
                ip (InetAddress/getLocalHost)
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
