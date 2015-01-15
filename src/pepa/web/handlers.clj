(ns pepa.web.handlers
  (:require [pepa.db :as db]
            [pepa.model :as m]
            [pepa.web.html :as html]
            [pepa.util :refer [slurp-bytes]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [hiccup.page :refer [html5]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :refer [redirect-after-post]]
            [ring.middleware.transit :refer [wrap-transit-response
                                             wrap-transit-body]]
            
            [liberator.core :refer [defresource]]
            [liberator.representation :refer [as-response]]
            [io.clojure.liberator-transit])
  (:import java.io.ByteArrayOutputStream
           java.io.ByteArrayInputStream
           java.io.FileInputStream
           java.net.URLEncoder
           java.sql.SQLException))

(def +default-media-types+ ["text/html"
                            "application/json"
                            "application/transit+json"])

(defresource file-scanner
  :allowed-methods #{:post}
  :available-media-types +default-media-types+
  :malformed? (fn [ctx]
                (let [req (:request ctx)]
                  (if-let [files (case (:content-type req)
                                   "message/rfc822" (m/mime-message->files (:body req))
                                   "application/pdf" [{:data (slurp-bytes (:body req))
                                                       :name (get-in req [:headers "x-filename"])
                                                       :content-type (:content-type req)}]
                                   false)]
                    [false {::files files}]
                    [true {::error (str "Unsupported content type: " (:content-type req))}])))
  :handle-malformed ::error
  :handle-ok (fn [ctx]
               "Created")
  :post! (fn [ctx]
           (m/store-files! (get-in ctx [:request ::db])
                           (::files ctx)
                           {:origin "scanner"})))

(defresource page-image [id size]
  :allowed-methods #{:get}
  :available-media-types ["image/png"]
  :exists? (fn [ctx]
             (let [db (get-in ctx [:request ::db])
                   config (get-in ctx [:request ::config])
                   dpi (or
                        (get-in config [:rendering :png :dpi size])
                        (get-in config [:web :default-page-dpi]))
                   [{:keys [image hash]}] (db/query db ["SELECT image, hash FROM page_images WHERE page = ? AND dpi = ?"
                                                        (Integer/parseInt id) dpi])]
               (when image
                 {::page (ByteArrayInputStream. image)
                  ::hash hash})))
  :etag ::hash
  :handle-ok ::page)

(defresource page-rotation [id]
  :allowed-methods #{:post}
  :available-media-types +default-media-types+
  :exists? (fn [ctx]
             (let [db (get-in ctx [:request ::db])
                   [{:keys [id]}] (db/query db ["SELECT id FROM pages WHERE id = ?" (Integer/parseInt id)])]
               (when id {::page-id id})))
  :can-post-to-missing? false
  :malformed? (fn [ctx]
                (let [rotation (get-in ctx [:request :body :rotation])]
                  (if (and (integer? rotation)
                           (zero? (mod rotation 90)))
                    [false {::rotation (mod rotation 360)}]
                    [true "Invalid rotation"])))
  :post! (fn [ctx]
           (let [db (get-in ctx [:request ::db])
                 id (::page-id ctx)
                 rotation (::rotation ctx)]
             (m/rotate-page db id rotation))))

(defresource document [id]
  :allowed-methods #{:get :post}
  :available-media-types +default-media-types+
  :exists? (fn [ctx]
             (let [id (Integer/parseInt id)]
               (when-let [d (m/get-document (get-in ctx [:request ::db]) id)]
                 {::document d
                  ::id id})))
  :post-redirect? true
  :location (fn [ctx] (str "/documents/" id))
  :post! (fn [ctx]
           (let [id (::id ctx)
                 req (:request ctx)
                 params (:body req)
                 attrs (select-keys params [:title])
                 attrs (into {} (remove (comp nil? val) attrs))
                 {added-tags :added, removed-tags :removed} (:tags params)]
             (assert (every? string? added-tags))
             (assert (every? string? removed-tags))
             (db/with-transaction [db (::db req)]
               (m/update-document! db id attrs added-tags removed-tags)
               {::document (m/get-document db id)})))
  :handle-created ::document
  :handle-ok (fn [ctx]
               (let [document (::document ctx)]
                 (condp = (get-in ctx [:representation :media-type])
                   "text/html" (html/document document)
                   document))))

(defn ^:private sanitize-document-title [title]
  (-> title
      (s/replace " " "-")
      (s/lower-case)
      (URLEncoder/encode)
      ;; TODO(mu): Handle different filetypes
      (str ".pdf")))

(defn ^:private deleting-file-input-stream
  "Creates an input-stream which will delete FILE on close."
  [file]
  (proxy [FileInputStream] [file]
    (close []
      (try
        (.delete file)
        (finally
          (proxy-super close))))))

(defresource document-download [id]
  :allowed-methods #{:get}
  :available-media-types ["application/pdf"]
  :exists? (fn [ctx]
             (let [db (get-in ctx [:request ::db])
                   id (Integer/parseInt id)
                   [{:keys [title]}]
                   (db/query db ["SELECT title FROM documents WHERE id = ?" id])]
               (when title
                 {::title title
                  ::pdf-file (deleting-file-input-stream (m/document-pdf db id))
                  ::filename (str "attachment; filename=\""
                                  (sanitize-document-title title)
                                  "\"")})))
  :as-response (fn [d ctx]
                 (-> (as-response d ctx)
                     (assoc-in [:headers "content-disposition"] (::filename ctx))))
  :handle-ok ::pdf-file)

(defresource inbox
  :allowed-methods #{:get :delete}
  :available-media-types +default-media-types+
  :delete! (fn [ctx]
             (let [db (get-in ctx [:request ::db])]
               (when-let [pages (seq (get-in ctx [:request :body]))]
                 (m/remove-from-inbox! db pages))))
  :handle-ok (fn [ctx]
               (let [pages (m/inbox (get-in ctx [:request ::db]))]
                 (condp = (get-in ctx [:representation :media-type])
                   "text/html" (html/inbox pages)
                   pages))))

(defresource documents
  :allowed-methods #{:get :post}
  :available-media-types +default-media-types+
  :malformed? (fn [ctx]
                (try
                  (let [db (get-in ctx [:request ::db])]
                    (if-let [query (some-> ctx
                                           (get-in [:request :query-params "q"])
                                           (edn/read-string))]
                      [false {::query query
                              ::results (m/query-documents db query)}]
                      [false {::results (m/query-documents db)}]))
                  (catch RuntimeException e
                    [true {::error "Invalid query string"}])
                  (catch SQLException e
                    [true {::error "Query string generated invalid SQL"}])))
  :post! (fn [{:keys [request, representation] :as ctx}]
           (db/with-transaction [conn (::db request)]
             (let [params (:body request)
                   file (:upload/file params)
                   pages (seq (:pages params))
                   origin (or
                           (:origin params)
                           (:origin file)
                           "web")]
               ;; XOR: Either pages OR file
               (assert (and (or pages file)
                            (not (and pages file))))
               (let [attrs (select-keys params [:title :notes :tags])
                     attrs (cond
                             file
                             (let [{:keys [content-type filename data]} file
                                   file (m/store-file! conn {:content-type content-type
                                                             :name filename
                                                             :origin origin
                                                             :data data})]
                               (assoc attrs :file (:id file)))
                             pages
                             (assoc attrs :page-ids pages))
                     id (m/create-document! conn (assoc attrs :origin origin))
                     tagging (get-in request [::config :tagging])]
                 (m/auto-tag! conn id tagging
                              {:origin origin})
                 {::document (m/get-document conn id)}))))
  :handle-created ::document
  :handle-ok (fn [ctx]
               ;; TODO: Order by creation time once we have that?
               (let [req (:request ctx)
                     documents (::results ctx)]
                 (case (get-in ctx [:representation :media-type])
                   "text/html" (html/documents documents)
                   (->> documents
                        (map :id)
                        (into [])))))
  :handle-malformed ::error)

(defresource tags
  :available-media-types +default-media-types+
  :allowed-methods #{:get}
  :handle-ok (fn [ctx]
               (let [tags (db/query (get-in ctx [:request ::db])
                                    "SELECT t.name, COUNT(dt.document) FROM tags AS t JOIN document_tags AS dt ON dt.tag = t.id GROUP BY t.name")]
                 (condp = (get-in ctx [:representation :media-type])
                   "text/html" (html/tags (map :name tags))
                   tags))))

(defn handle-get-objects-for-tag [req tag]
  (db/with-transaction [conn (::db req)]
    (let [[{tag-id :id}] (db/query conn ["SELECT id FROM tags WHERE name = ?" tag])
          files (db/query conn ["SELECT f.id, f.origin, f.name FROM files AS f JOIN file_tags AS ft ON f.id = ft.file WHERE ft.tag = ?" tag-id])
          pages (db/query conn ["SELECT p.id, dp.page FROM pages AS p JOIN document_pages AS dp ON p.id = dp.document JOIN page_tags AS pt ON p.id = pt.page WHERE pt.tag = ?" tag-id])
          documents (db/query conn ["SELECT d.id, d.title FROM documents AS d JOIN document_tags AS dt ON d.id = dt.document WHERE dt.tag = ?" tag-id])]
      {:status 200
       :body (html/objects-for-tag tag files pages documents)})))

(defresource documents-bulk 
  :allowed-methods #{:post}
  :available-media-types ["application/json"
                          "application/transit+json"]
  :exists? (fn [ctx]
             (when-let [ids (get-in ctx [:request :body])]
               {::ids ids
                ::documents (m/get-documents (get-in ctx [:request ::db]) ids)}))
  ;; Change the status code to 200
  :as-response (fn [d ctx]
                 (-> (as-response d ctx)
                     (assoc-in [:status] 200)))
  ;; NOTE: We still need to use the created-handler instead of ok
  :handle-created (fn [{documents ::documents}]
                    (zipmap (map :id documents) documents)))


(defn wrap-component [handler {:keys [config db]}]
  (fn [req]
    (handler (assoc req
               ::config config
               ::db db))))

(def handlers
  (routes (GET "/" [] (html/root))
          (ANY "/inbox" [] inbox)
          (ANY "/files/scanner" [] file-scanner)

          (ANY "/pages/:id/image/:size" [id size]
               (page-image id size))
          (ANY "/pages/:id/image" [id]
               (page-image id "full"))
          (ANY "/pages/:id/rotation" [id]
               (page-rotation id))

          (ANY "/documents" [] documents)
          (ANY "/documents/bulk" [] documents-bulk)
          (ANY "/documents/:id" [id]
               (document id))
          (ANY "/documents/:id/download" [id]
               (document-download id))

          (ANY "/tags" [] tags)
          (GET "/tags/:tag" [tag :as req]
               (handle-get-objects-for-tag req tag))

          (route/resources "/")
          (route/not-found "Nothing here")))

(defn wrap-logging [handler]
  (fn [req]
    (when (get-in req [::config :web :log-requests?])
      (pprint req))
    (handler req)))

(defn make-handlers [web-component]
  (-> #'handlers
      (wrap-transit-body)
      (wrap-params)
      (wrap-logging)
      (wrap-component web-component)))
