(ns pepa.processor.page-ocr
  (:require [com.stuartsierra.component :as component]
            [pepa.db :as db]
            [pepa.model :as m]
            [pepa.pdf :as pdf]
            [pepa.processor :as processor :refer [IProcessor]]
            [pepa.util :refer [run-process with-temp-file]]
            [pepa.log :as log]
            
            [clojure.string :as s]
            [clojure.java.io :as io]))

(defrecord PageOcr [config db processor])

(defn make-component []
  (map->PageOcr {}))

(defmulti run-ocr (fn [engine & _] engine))

(defmethod run-ocr :cuneiform [_ config lang result-file image-file]
  (let [timeout (:timeout config)]
    (run-process "cuneiform" ["-l" lang "-o" result-file image-file]
                 {} timeout)))

(defmethod run-ocr :tesseract [_ config lang result-file image-file]
  (let [outbase (subs result-file 0 (- (.length result-file) 3))
        timeout (:timeout config)]
    (run-process "tesseract" [image-file outbase "-l" lang]
                 {} timeout)))

(defn ^:private run-ocr-fn [ocr config]
  (fn [image-file]
    (with-temp-file [result-file nil ".txt"]
      (let [result (java.io.StringWriter.)]
        (doseq [engine [:cuneiform :tesseract]]
          (let [engine-config (get config engine)]
            (when (:enable engine-config)
              (doseq [lang (:languages engine-config)]
                (try
                  (log/debug ocr "OCR engine" engine "with langauge" lang)
                  (run-ocr engine engine-config lang (str result-file) (str image-file))
                  (io/copy result-file result)
                  (.write result "\n")
                  (catch Exception e
                    (prn e)))))))
        (if (zero? (.length (str result)))
          [:processing-status/failed]
          [:processing-status/processed (str result)])))))

(extend-type PageOcr
  IProcessor
  (next-item [component]
    "SELECT p.id, p.number, f.data
     FROM pages AS p 
     JOIN files AS f ON p.file = f.id
     WHERE p.ocr_status = 'pending'
     ORDER BY p.number, p.id
     LIMIT 1")

  (process-item [component page]
    (let [db (:db component)]
      (log/info component "running OCR on page" (:id page))
      (pdf/with-reader [pdf (:data page)]
        (let [run-ocr (run-ocr-fn component (get-in component [:config :ocr]))
              [status text] (pdf/call-with-rendered-page-file pdf (:number page) :png 300 run-ocr)]
          (log/debug component "Got OCR result for page" (:id page)
                     {:status status
                      :text-length (count text)})
          (db/update! db
                      :pages
                      {:ocr_text text
                       :ocr_status status}
                      ["id = ?" (:id page)])))))

  component/Lifecycle
  (start [component]
    (if (get-in component [:config :ocr :enable])
      (do
        (log/info component "Starting page OCR processor")
        (assoc component
               :processor (processor/start component :pages/new)))
      component))

  (stop [component]
    (when-let [processor (:processor component)]
      (log/info component "Stopping page OCR processor")
      (processor/stop processor))
    (assoc component
           :processor nil)))

(defmethod clojure.core/print-method PageOcr
  [ocr ^java.io.Writer writer]
  (.write writer (str "#<OcrRenderer "
                      (if (:processor ocr) "active" "disabled")
                      ">")))
