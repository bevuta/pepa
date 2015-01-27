(ns pepa.api.upload
  (:require [cljs.core.async :as async :refer [<!]]
            [pepa.api :refer [xhr-request!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def allowed-file-type? #{"application/pdf"})

(defn ^:private drop-valid? [event]
  (some #{"Files"} (array-seq event.dataTransfer.types)))

(defn accept-file-drag [event]
  (let [types (array-seq event.dataTransfer.types)]
    (when (some #{"Files"} types)
      (.preventDefault event))))

(defn data-transfer-files [dt]
  (filterv #(allowed-file-type? (.-type %))
          (array-seq (.-files dt))))

(defn file->u8arr [file]
  (let [ch (async/chan)]
    (if-not (allowed-file-type? (.-type file))
      (async/close! ch)
      (let [file-reader (js/FileReader.)]
        (set! (.-onload file-reader)
              (fn [e]
                (if-let [file-content e.target.result]
                  (async/put! ch (js/Uint8Array. file-content))
                  (async/close! ch))))
        (.readAsArrayBuffer file-reader file)))
    ch))

(defn upload-to-inbox! [blob & [progress]]
  "Uploads blob from drop EVENT to server's inbox."
  (go
    (let [response (<! (xhr-request! "/files/scanner" :post
                                     "application/transit+json" blob
                                     nil ; no timeout
                                     progress))]
      (if (= 201 (:status response))
        :success
        (println "Upload error: " response)))))

(defn upload-document! [document {:keys [blob content-type filename]} & [progress]]
  "Uploads blob from drop EVENT to server's inbox."
  (go
    (let [response (<! (xhr-request! "/documents" :post "application/transit+json"
                                     (-> (into {} document)
                                         (assoc :upload/file {:data blob
                                                              :content-type content-type
                                                              :name filename}))
                                     nil ; no timeout
                                     progress))]
      (if (= 201 (:status response))
        (-> response :response/transit :id)
        (println "Upload error: " response)))))
