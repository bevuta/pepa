(ns pepa.printing
  (:require [com.stuartsierra.component :as component]
            [clojure.java.io :as io]

            [lpd.server :as lpd]
            [lpd.protocol :as lpd-protocol]

            [pepa.db :as db]
            [pepa.model :as model]
            [pepa.log :as log]
            [pepa.util :as util]

            [pepa.zeroconf :as zeroconf])
  (:import java.lang.ProcessBuilder
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
      (log/info "got job on queue" queue
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
                             (log/warn "Got print job. Interpreting it as Postscript")
                             (ps->pdf data))))
              file-props {:content-type "application/pdf"
                          :name name
                          :origin (str "printer/" queue)
                          :data (util/slurp-bytes pdf)}
              file (model/store-file! db file-props)
              origin "printer"]
          ;; TODO: Allow printing to inbox
          (when-not false ; (model/inbox-origin? (:config lpd) origin)
            (log/info "Creating Document for file" (:id file))
            (let [document (model/create-document! db {:title (:name file-props)
                                                       :file (:id file)})
                  tagging-config (get-in lpd [:config :tagging])]
              (log/info "Auto-tagging document" document)
              (model/auto-tag! db document tagging-config
                               {:origin origin
                                :printing/queue (str "printer/" queue)}))))))))

(defn ^:private lpd-server [lpd config]
  (lpd/make-server (assoc (select-keys config [:host :port])
                          :handler (job-handler lpd))))

(defrecord LPDPrinter [config db server]
  component/Lifecycle
  (start [lpd]
    (let [lpd-config (get-in config [:printing :lpd])]
      (if-not (:enable lpd-config)
        lpd
        (do
          (assert (< 0 (:port lpd-config) 65535))
          (log/info "Starting LPD Server")
          (let [server (-> (lpd-server lpd lpd-config)
                           (lpd/start-server))]
            (assoc lpd
                   :server server))))))
  (stop [lpd]
    (when-let [server (:server lpd)]
      (log/info "Stopping LPD Server")
      (lpd/stop-server server))
    (assoc lpd
           :mdns nil
           :server nil)))

(defn make-lpd-component []
  (map->LPDPrinter {}))

;;; Zeroconf Service Implementation

(defmethod zeroconf/service-info :lpd [module config]
  (let [name "Pepa DMS Printer"
        port (get-in config [:printing :lpd :port])
        queue "documents"]
    (assert (< 0 port 65535))
    {:type "_printer._tcp.local."
     :name name
     :port port
     :props {"pdl" "application/pdf,application/postscript"
             "rp" queue
             "txtvers" "1"
             "qtotal" "1" ;count of queues
             "ty" (str name " (Queue: " queue ")")}}))
