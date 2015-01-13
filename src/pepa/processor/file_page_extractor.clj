(ns pepa.processor.file-page-extractor
  (:require [com.stuartsierra.component :as component]
            [pepa.db :as db]
            [pepa.model :as m]
            [pepa.pdf :as pdf]
            [pepa.processor :as processor :refer [IProcessor]]
            [clojure.string :as s]))

(defrecord FilePageExtractor [config db processor])

(defn make-component []
  (map->FilePageExtractor {}))

(defn ^:private log [& args]
  (apply println args))

(defn ^:private extract-pages [db file-id data]
  (pdf/with-reader [pdf data]
    (doseq [page (range 0 (pdf/page-count pdf))]
      (log "processing page" page)
      (let [text (-> (pdf/extract-page-text pdf page)
                     ;; Postgres can't handle NULL-bytes in TEXT
                     (s/replace "\0" ""))]
        (db/insert! db :pages
                    {:file file-id
                     :number page
                     :text text})))))

(defn ^:private inbox-origin? [config origin]
  (contains? (set (get-in config [:inbox :origins])) origin))

(extend-type FilePageExtractor
  IProcessor
  (next-item [component]
    "SELECT id, content_type, data, origin FROM files WHERE status = 'pending' ORDER BY id LIMIT 1")

  (process-item [component file]
    (let [{content-type :content_type :keys [id data origin]} file
          config (:config component)]
      (log "start processing of file" id (str "(" (count data) " bytes, origin: " origin ")"))
      (db/with-transaction [db (:db component)]
        (let [update (try
                       (extract-pages db id data)
                       (db/notify! db :pages/new)
                       {:status :processing-status/processed
                        :report "OK"}
                       (catch Exception e
                         (println "Got exception:" e)
                         (.printStackTrace e)
                         {:status :processing-status/failed
                          :report (str e)}))]
          (db/update! db :files update ["id = ?" id])
          (when (= :processing-status/processed (:status update))
            ;; For every linked document, add all pages
            (doseq [document (m/file-documents db id)]
              (println "Adding pages from file" id "to document" document)
              (m/link-file! db document id))
            ;; Move to inbox if the file's origin dictates that
            (when (inbox-origin? config origin)
              (println "Moving pages from file" id "to inbox")
              (m/add-to-inbox! db (m/page-ids db id))))))
      (log "finish processing of file" id)))

  component/Lifecycle
  (start [component]
    (println ";; Starting file page extractor")
    (assoc component
      :processor (processor/start component :files/new)))

  (stop [component]
    (println ";; Stopping file page extractor")
    (when-let [processor (:processor component)]
      (processor/stop processor))
    (assoc component
      :processor nil)))
