(ns pepa.processor.file-page-extractor
  (:require [com.stuartsierra.component :as component]
            [pepa.db :as db]
            [pepa.model :as m]
            [pepa.pdf :as pdf]
            [pepa.processor :as processor :refer [IProcessor]]
            [pepa.log :as log]
            [clojure.string :as s]))

(defn ^:private extract-pages [processor file-id data]
  (pdf/with-reader [pdf data]
    (doseq [page (range 0 (pdf/page-count pdf))]
      (log/info processor "Processing page" page)
      (log/debug processor "Extracting text of page" page)
      (let [text (-> pdf
                     (pdf/extract-page-text page)
                     ;; Postgres can't handle NULL-bytes in TEXT
                     (s/replace "\0" ""))]
        (log/debug processor "Inserting page" page "into the database")
        (db/insert! (:db processor) :pages
                    {:file file-id
                     :number page
                     :text text})))))

(defrecord FilePageExtractor [config db processor]
  IProcessor
  (next-item [component]
    "SELECT id, content_type, data, origin FROM files WHERE status = 'pending' ORDER BY id LIMIT 1")

  (process-item [component file]
    (let [{content-type :content_type :keys [id data origin]} file]
      (log/info component "Start processing file" id (str "(" (count data) " bytes, origin: " origin ")"))
      (db/with-transaction [db (:db component)]
        (let [update (try
                       (extract-pages component id data)
                       (db/notify! db :pages/new)
                       {:status :processing-status/processed
                        :report "OK"}
                       (catch Exception e
                         (log/error component e "Exception in `process-item'.")
                         (.printStackTrace e)
                         {:status :processing-status/failed
                          :report (str e)}))]
          (db/update! db :files update ["id = ?" id])
          (when (= :processing-status/processed (:status update))
            ;; For every linked document, add all pages
            (doseq [document (m/file-documents db id)]
              (log/info component "Adding pages from file" id "to document" document)
              (m/process-file-link! db document))
            ;; Move to inbox if the file's origin dictates that
            (when (m/inbox-origin? config origin)
              (log/info component "Moving pages from file" id "to inbox")
              (m/add-to-inbox! db (m/page-ids db id))))
          ;; Error Case. For now, log the error & remove all tags from
          ;; the document, effectively hiding it (in an ugly way)
          (when (= :processing-status/failed (:status update))
            (log/warn component (str "Failed to render file " id))
            (doseq [document (m/file-documents db id)]
              (m/remove-all-tags! db document)))))
      (log/info component "Finished processing of file" id)))

  component/Lifecycle
  (start [component]
    (log/info component "Starting file page extractor")
    (assoc component
           :processor (processor/start component :files/new)))

  (stop [component]
    (log/info component "Stopping file page extractor")
    (when-let [processor (:processor component)]
      (processor/stop processor))
    (assoc component
           :processor nil)))

(defn ^:private retry-all! [component]
  (db/with-transaction [db (:db component)]
    (db/update! db :files
                {:status :processing-status/pending
                 :report nil}
                ["status = 'failed'"])
    ;; TODO: :files/updated would be correct, but the Processor doesn't
    ;; listens on that channel
    (db/notify! db :files/new)))

(defn make-component []
  (map->FilePageExtractor {}))

(defmethod clojure.core/print-method FilePageExtractor
  [ext ^java.io.Writer writer]
  (.write writer (str "#<PageRenderer "
                      (if (:processor ext) "active" "disabled")
                      ">")))
