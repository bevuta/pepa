(ns pepa.model
  (:require [pepa.db :as db]
            [pepa.pdf :as pdf]
            [pepa.model.query :as query]
            [pepa.mime :as mime]
            [pepa.util :refer [slurp-bytes with-temp-file]]
            [clojure.string :as s]
            [clojure.set :as set]
            [clojure.java.io :as io]))

;;; File Handling

(defn processing-status [db file]
  (-> (db/query db ["SELECT status FROM files WHERE id = ?" file])
      first
      :status))

(defn page-ids [db file]
  (->> (db/query db ["SELECT id FROM pages WHERE file = ? ORDER BY number" file])
       (map :id)))

(defn store-file! [db {:keys [content-type origin name data]}]
  (assert content-type)
  (assert origin)
  (assert data)
  (let [file (db/insert! db
                         :files
                         {:content_type content-type
                          :origin origin
                          :name name
                          :data data})]
    (db/notify! db :files/new)
    (first file)))

(defn store-files! [db files extra-attrs]
  (db/with-transaction [db db]
    (doall (map (fn [file]
                  (store-file! db (merge extra-attrs file)))
                files))))

(defn file-documents
  "Returns a list of document-ids which are linked to FILE."
  [db file]
  (->> (db/query db ["SELECT id from documents WHERE file = ?" file])
       (map :id)))

;;; Page Functions

(defn rotate-page [db page-id rotation]
  (assert (zero? (mod rotation 90)))
  (db/with-transaction [db db]
    (db/notify! db :pages/updated)
    (db/update! db :pages {:rotation rotation} ["id = ?" page-id])))

(def ^:private pg-array->set (comp set #(.getArray %)))

(defn get-pages [db ids]
  (->> (pepa.db/query db (db/sql+placeholders
                          "SELECT id, rotation, number, render_status AS \"render-status\", ocr_status AS \"ocr-status\",
                             (SELECT array_agg(dpi) from page_images where page = id) as dpi
                           FROM pages
                           WHERE id IN (%s)"
                          ids))
       ;; Make  a set out of the strange Postgres-Array
       (map #(update-in % [:dpi] pg-array->set))))



;;; Document Functions

(defn ^:private dissoc-fks [fk rows]
  (map #(dissoc % fk) rows))

(defn ^:private get-associated [db sql ids fk]
  (db/query db
            (db/sql+placeholders sql ids)
            :result-set-fn (fn [result]
                             (let [grouped (group-by fk result)]
                               (zipmap (keys grouped)
                                       (map (partial dissoc-fks fk)
                                            (vals grouped)))))))

(defn get-documents [db ids]
  (db/with-transaction [conn db]
    (let [documents (db/query conn (db/sql+placeholders "SELECT id, title, created, modified, notes FROM documents WHERE id IN (%s)" ids))
          pages (get-associated conn "SELECT dp.document, p.id, p.rotation, p.render_status AS \"render-status\",
                                        (SELECT array_agg(dpi) from page_images where page = id) AS dpi
                                      FROM pages AS p
                                      JOIN document_pages AS dp
                                        ON dp.page = p.id
                                      WHERE dp.document IN (%s)
                                      ORDER BY dp.number"
                                ids :document)
          tags (get-associated conn "SELECT dt.document, t.name FROM document_tags AS dt JOIN tags AS t ON t.id = dt.tag WHERE dt.document IN (%s) ORDER BY dt.seq" ids :document)]
      (map (fn [{:keys [id] :as document}]
             (assoc document
                    :pages (->> (vec (get pages id))
                                (map #(update-in % [:dpi] pg-array->set)))
                    :tags (mapv :name (get tags id))))
           documents))))

(defn get-document [db id]
  (first (get-documents db [id])))

;;; Query Documents

(def ^:private documents-base-query
  "SELECT d.id, d.title, dp.page FROM documents AS d JOIN document_pages AS dp ON dp.document = d.id AND dp.number = 0")

(defn ^:private documents-query [query]
  (let [[condition params] (query/->sql query)]
    (cons (str documents-base-query
               " LEFT JOIN document_tags AS dt ON dt.document = d.id LEFT JOIN tags AS t ON dt.tag = t.id GROUP BY d.id, d.title, dp.page HAVING "
               condition)
          params)))

(defn query-documents
  ([db]
   (db/query db documents-base-query))
  ([db query]
   (if (nil? query)
     (query-documents db)
     (db/query db (documents-query query)))))

(defn document-file
  "Returns the id of the file associated to document with ID. Might be
  nil."
  [db id]
  (-> (db/query db ["SELECT file FROM documents WHERE id = ?" id])
      first
      :file))

(defn document-pages
  "Returns a list of pages for the document with ID."
  [db id]
  (db/query db ["SELECT p.file, p.number, p.rotation
                 FROM pages AS p
                 JOIN document_pages AS dp on dp.page = p.id
                 WHERE dp.document = ?
                 ORDER BY dp.number" id]))

;;; Modify Documents

(defn add-pages*! [db document page-ids]
  (assert (every? number? page-ids))
  (db/insert-coll! db :document_pages
                   (map (fn [page number]
                          {:document document
                           :page page
                           :number number})
                        page-ids
                        (range))))

(defn add-pages! [db document page-ids]
  (db/with-transaction [db db]
    (add-pages*! db document page-ids)
    (db/notify! db :documents/updated {:id document})))

(defn ^:private link-file*!
  "Sets a db-attribute to tell the processor to link all pages from
  FILE to DOCUMENT. Also handles already processed files."
  [db document file]
  (db/with-transaction [conn db]
    (db/update! conn :documents {:file file} ["id = ?" document])
    ;; Link pages if file is already processed
    (when (= :processing-status/processed (processing-status conn file))
      (add-pages*! conn document (page-ids conn file)))))

(defn link-file! [db document file]
  (db/with-transaction [db db]
    (link-file*! db document file)
    (db/notify! db :documents/updated {:id document})))

(declare add-tags*! remove-tags*!)

(defn create-document!
  [db {:keys [title tags notes
              page-ids file]}]
  (assert (every? string? tags))
  (assert (or (nil? notes) (string? notes)))
  (assert (let [p (seq page-ids)
                q file]
            ;; XOR
            (and (or p q) (not (and p q))))
          "Can't pass page-ids AND file.")
  (db/with-transaction [conn db]
    (let [[{:keys [id]}] (db/insert! conn :documents
                                     {:title title
                                      :notes notes})]
      (when (seq tags)
        (add-tags*! conn id tags))
      (when (seq page-ids)
        (add-pages*! conn id page-ids))
      (when file
        (link-file*! conn id file))
      (db/notify! db :documents/new {:id id})
      id)))

(defn update-document!
  "Updates document with ID. PROPS is a map of changed
  properties (currently only :title), ADDED-TAGS and REMOVED-TAGS are
  lists of added and removed tag-values (strings)."
  [db id props added-tags removed-tags]

  (assert (every? string? added-tags))
  (assert (every? string? removed-tags))
  (assert (every? #{:title} (keys props)))

  (db/with-transaction [conn db]
    (if-let [document (get-document conn id)]
      (let [added-tags (set added-tags)
            removed-tags (set removed-tags)
            ;; Subtract removed-tags from added-tags so we don't
            ;; create tags which will be removed instantly
            added-tags (set/difference added-tags removed-tags)]
        (remove-tags*! conn id removed-tags)
        (add-tags*! conn id added-tags)
        ;; Update document title if necessary
        (when (seq props)
          (db/update! conn :documents
                      props
                      ["id = ?" id]))
        (db/notify! conn :documents/updated {:id id}))
      (throw (ex-info (str "Couldn't find document with id " id)
                      {:document/id id
                       :db db})))))

;;; Tag Functions

(defn normalize-tags [tags]
  (assert (every? string? tags))
  (->> tags
       (map s/lower-case)
       (map s/trim)
       (set)))

(defn ^:private tag->db-tag [tag]
  (assert (string? tag))
  {:name tag})

(defn origin-tag [origin]
  (when-not (s/blank? origin)
    (str "origin/" origin)))

(defn document-tags [db document-id]
  (map :tag (db/query db ["SELECT tag FROM document_tags WHERE document = ?" document-id])))

(defn get-or-create-tags!
  "Gets tags for TAG-VALUES from DB. Creates them if necessary."
  [db tag-values]
  ;; Nothing to do if TAG-VALUES is empty
  (if-not (seq tag-values)
    tag-values
    (db/with-transaction [conn db]
      (let [tag-values (normalize-tags tag-values)
            ;; NOTE: It's important that tag-values is not empty
            existing (db/query conn (db/sql+placeholders "SELECT id, name FROM tags WHERE name IN (%s)" tag-values))
            new (set/difference tag-values (set (map :name existing)))
            new (map tag->db-tag new)
            new (db/insert-coll! conn :tags new)]
        (concat existing new)))))

(defn ^:private add-tags*! [db document-id tags]
  (assert (number? document-id))
  (assert (every? string? tags))
  (db/with-transaction [db db]
    (let [db-tags (get-or-create-tags! db tags)
          old-tags (document-tags db document-id)
          new-tags (set/difference (set (map :id db-tags))
                                   (set old-tags))]
      (when (seq new-tags)
        (db/insert-coll! db :document_tags
                         (for [tag new-tags]
                           {:document document-id :tag tag}))))))

(defn add-tags! [db document-id tags]
  (db/with-transaction [db db]
    (add-tags*! db document-id tags)
    (db/notify! db :documents/updated {:id document-id, :tags/new tags})))

(defn ^:private remove-tags*! [db document-id tags]
  (assert (every? string? tags))
  (assert (number? document-id))
  (when (seq tags)
    (db/with-transaction [db db]
      (when-let [tags (seq (db/query db (db/sql+placeholders "SELECT id, name FROM tags WHERE name IN (%s)" tags)))]
        (db/delete! db :document_tags
                    ;; TODO(mu): OH GOD PLEASE DON'T
                    (concat (db/sql+placeholders "tag IN (%s) AND document = ?" (map :id tags))
                            [document-id]))))))

(defn remove-tags! [db document-id tags]
  (db/with-transaction [db db]
    (remove-tags*! db document-id tags)
    (db/notify! db :documents/updated {:id document-id, :tags/removed tags})))

(defn auto-tag*! [db document-id tagging-config
                  {:keys [origin] :as data}]
  (let [tags (concat
              ;; Add origin-tag if enabled
              (when (:add-origin? tagging-config)
                [(origin-tag origin)])
              ;; Add sender-address as tag if configured
              (when (:mail/to tagging-config)
                [(:mail/to data)])
              (when (:mail/from tagging-config)
                [(:mail/from data)])
              ;; Add 'automatic' initial tags
              (when-let [tags (-> tagging-config :new-document seq)]
                (set tags)))]
    (add-tags*! db document-id (remove s/blank? tags))))

(defn auto-tag! [db document-id tagging-config data]
  (db/with-transaction [db db]
    (auto-tag*! db document-id tagging-config data)
    (db/notify! db :documents/updated {:id document-id})))

;;; Misc. Functions

(defn inbox
  "Returns a vector of pages in the Inbox sorted by file and number."
  [db]
  (vec (db/query db ["SELECT p.id, p.file, p.number, p.render_status AS \"render-status\"
                      FROM inbox AS i
                      LEFT JOIN pages AS p
                        ON i.page = p.id
                      ORDER BY p.file, p.number"])))

(defn add-to-inbox!
  "Unconditionally adds PAGES to inbox."
  [db page-ids]
  (db/with-transaction [db db]
    (db/notify! db :inbox/added {:pages page-ids})
    (db/insert-coll! db :inbox (for [id page-ids] {:page id}))))

(defn remove-from-inbox! [db page-ids]
  (db/with-transaction [db db]
    (db/notify! db :inbox/removed {:pages page-ids})
    (db/delete! db :inbox (db/sql+placeholders "page IN (%s)" page-ids))))

;;; TODO(mu): We need to cache this stuff.
;;; TODO: Handle render-status here
(defn document-pdf
  "Generates a pdf-file for DOCUMENT-ID. Returns a file-object
  pointing to the (temporary) file. Caller must make sure to delete
  this file."
  [db document-id]
  ;; If the document has an associated file we can short-circuit the split&merge path
  (let [pages (document-pages db document-id)]
    (if-let [document-file (and (every? #(zero? (:rotation %)) pages)
                                (document-file db document-id))]
      (let [f (java.io.File/createTempFile "pepa" ".pdf")
            data (-> (db/query db ["SELECT data FROM files WHERE id = ?" document-file]) first :data)]
        (with-open [out (io/output-stream f)]
          (io/copy data out)
          (.flush out))
        f)
      ;; ...if not: Split all source PDFs and merge the pages together
      (let [files (db/query db (db/sql+placeholders "SELECT f.id, f.data FROM files as f WHERE f.id in (%s)"
                                                    (into #{} (map :file pages))))
            files (zipmap (map :id files) files)
            ;; file-id -> (page-number -> pdf-file)
            page-files (into {}
                             ;; Group pages by file so we don't create two temp files if we
                             ;; want two pages from the same document
                             (for [[file-id pages] (group-by :file pages)]
                               [file-id
                                (let [data (get-in files [file-id :data])]
                                  (pdf/split-pdf data (map :number pages)))]))
            pages (map #(assoc % :pdf-file (get-in page-files [(:file %) (:number %)])) pages)]
        (let [page-files (map :pdf-file pages)]
          (try
            ;; Rotate the pages if necessary
            (let [page-files (for [page pages]
                               (let [original (:pdf-file page)]
                                 (pdf/rotate-pdf-file original (:rotation page))))]
              (pdf/merge-pages page-files))
            (finally
              (doseq [file page-files]
                (.delete file)))))))))

(defn mime-message->files [input]
  (->> (mime/message-parts input)
       (filter mime/pdf-part?)
       (map #(hash-map :name (:filename %)
                       :data (slurp-bytes @(:content %))
                       :content-type "application/pdf"))))

;;; Sequence Number Stuff

(def ^:private sequenced-tables
  [:pages :page_images
   :files
   :documents :document_tags :document_pages
   :inbox
   :deletions])

(defn valid-seqs? [seq]
  (and (every? (set sequenced-tables) (keys seq))
       (every? integer? (vals seq))
       (= (set sequenced-tables) (set (keys seq)))))

(let [names (map name sequenced-tables)
      where (s/join ", " (map #(str % "_state_seq as " %) names))
      select (s/join ", " (map #(str % ".current as " %) names))
      query (str "SELECT " select " FROM " where)]
  (defn sequence-numbers [db]
    (-> db
        (db/query [query])
        (first))))

(defmulti ^:private changed-entities* (fn [db table seq-num] table))

(defmethod changed-entities* :default [_ _ _] nil)

(defmethod changed-entities* :documents [db _ seq-num]
  {:documents (mapv :id (db/query db ["SELECT id FROM documents WHERE state_seq > ?" seq-num]))})

(defmethod changed-entities* :document_pages [db _ seq-num]
  {:documents (->> (db/query db ["SELECT DISTINCT d.id
                                  FROM document_pages AS dp
                                  LEFT JOIN documents as d
                                    ON d.id = dp.document
                                  WHERE dp.state_seq > ?" seq-num])
                   (mapv :id))})

(defmethod changed-entities* :document_tags [db _ seq-num]
  {:documents (->> (db/query db ["SELECT DISTINCT d.id
                                  FROM document_tags AS dt
                                  LEFT JOIN documents as d
                                    ON d.id = dt.document
                                  WHERE dt.state_seq > ?" seq-num])
                   (mapv :id))})

(defmethod changed-entities* :pages [db _ seq-num]
  (let [pages (mapv :id (db/query db ["SELECT id FROM pages WHERE state_seq > ?" seq-num]))]
    {:pages pages
     :documents (when (seq pages)
                  (->>
                   (db/query db (db/sql+placeholders
                                 "SELECT DISTINCT dp.document
                                  FROM document_pages AS dp
                                  WHERE dp.page IN (%s)" pages))
                   (mapv :document)))}))

(defmethod changed-entities* :page_images [db _ seq-num]
  (let [pages (->>
               (db/query db ["SELECT DISTINCT p.id
                              FROM page_images as pi
                              LEFT JOIN pages as p ON p.id = pi.page
                              WHERE pi.state_seq > ?" seq-num])
               (map :id))]
    {:pages pages
     :documents (when (seq pages)
                  (->>
                   (db/query db (db/sql+placeholders
                                 "SELECT DISTINCT dp.document
                                  FROM document_pages AS dp
                                  WHERE dp.page IN (%s)" pages))
                   (mapv :document)))}))

(defmethod changed-entities* :files [db _ seq-num]
  {:files (->> (db/query db ["SELECT id FROM files WHERE state_seq > ?" seq-num])
               (mapv :id)
               (set))})

(defmethod changed-entities* :inbox [db _ seq-num]
  {:inbox (->> (db/query db ["SELECT page FROM inbox WHERE state_seq > ?" seq-num])
               (mapv :page)
               (set))})

(defmethod changed-entities* :deletions [db _ seq-num]
  {:deletions (reduce (fn [deletions {:keys [id entity]}]
                        (update-in deletions [(-> entity name keyword)] (comp set conj) id))
                      {}
                      (db/query db ["SELECT id, entity FROM deletions WHERE state_seq > ?" seq-num]))})

(defn changed-entities
  "Takes a seqs-map and returns a map containing IDs for all changed
  entities since that point in time. Map contains also a :seqs entry
  which is the new seqs-map."
  [db seqs]
  (if (valid-seqs? seqs)
    (db/with-transaction [db db]
      (let [m (apply (partial merge-with #(into (set %1) %2))
                     (map #(apply changed-entities* db %) seqs))]
        ;; Remove "empty" keys from map
        (when-let [kvs (seq (for [[k v] m :when (seq v)] [k v]))]
          (into {:seqs (sequence-numbers db)} kvs))))
    (throw (ex-info "Didn't get a valid (and complete) seqs-map!" {:seqs seqs}))))
