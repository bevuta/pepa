(ns pepa.web.handlers
  (:require [pepa.db :as db]
            [pepa.model :as m]
            [pepa.web.html :as html]
            [pepa.web.poll :refer [poll-handler]]
            [pepa.authorization :as auth]
            [pepa.log :as log]
            [pepa.util :refer [slurp-bytes]]
            
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.transit :refer [wrap-transit-response
                                             wrap-transit-body]]
            [ring.middleware.json :refer [wrap-json-body]]
            
            [liberator.core :refer [defresource]]
            [liberator.representation :refer [as-response]]
            io.clojure.liberator-transit

            [immutant.web.async :as async-web])
  (:import java.io.FileInputStream
           java.net.URLEncoder
           java.sql.SQLException))

(def +default-media-types+ ["text/html"
                            "application/json"
                            "application/transit+json"])

;;; TODO(mu): Write `defresource' macro that automatically adds
;;; defaults for :authorized? and :available-media-types.

(defresource file-scanner [web]
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
  ;; TODO(mu): :authorized?
  :handle-ok (fn [ctx]
               "Created")
  :post! (fn [ctx]
           (m/store-files! (:db web)
                           (::files ctx)
                           {:origin "scanner"})))

(defresource page [web id]
  :allowed-methods #{:get}
  :available-media-types ["application/transit+json"]
  :malformed? (fn [ctx]
                (try [false {::id (Integer/parseInt id)}]
                     (catch NumberFormatException e
                       true)))
  :authorized? (auth/authorization-fn web :page ::id)
  :exists? (fn [ctx]
             (let [[page] (m/get-pages (:db web) [(::id ctx)])]
               (when page
                 {::page (select-keys page [:id :render-status :rotation])})))
  :handle-ok ::page)

(defresource page-image [web id size]
  :allowed-methods #{:get}
  :available-media-types ["image/png"]
  :malformed? (fn [ctx]
                (try [false {::id (Integer/parseInt id)
                             ::size (if (= ::max size)
                                      size
                                      (Integer/parseInt size))}]
                     (catch NumberFormatException e
                       true)))
  :authorized? (auth/authorization-fn web :page ::id)
  :exists? (fn [ctx]
             (try
               (let [db (:db web)
                     [{:keys [image hash]}] (if (= size ::max)
                                              (db/query db ["SELECT image, hash FROM page_images WHERE page = ? ORDER BY dpi DESC LIMIT 1"
                                                            (::id ctx)])
                                              (db/query db ["SELECT image, hash FROM page_images WHERE page = ? AND dpi = ?"
                                                            (::id ctx) (::size ctx)]))]
                 (when image
                   {::page (io/input-stream image)
                    ::hash hash}))
               (catch NumberFormatException e
                 nil)))
  :etag ::hash
  :handle-ok ::page)

(defresource page-rotation [web id]
  :allowed-methods #{:post}
  :available-media-types +default-media-types+
  :malformed? (fn [ctx]
                (try
                  (let [id (Integer/parseInt id)
                        rotation (get-in ctx [:request :body :rotation])]
                    (if (and (integer? rotation)
                             (zero? (mod rotation 90)))
                      [false {::rotation (mod rotation 360)
                              ::id id}]
                      [true "Invalid rotation"]))
                  (catch NumberFormatException e
                    [true "Malformed ID"])))
  :authorized? (auth/authorization-fn web :page ::id)
  :exists? (fn [ctx]
             (let [[{:keys [id]}] (db/query (:db web) ["SELECT id FROM pages WHERE id = ?" (::id ctx)])]
               (when id {::page-id id})))
  :can-post-to-missing? false

  :post! (fn [ctx]
           (m/rotate-page (:db web)
                          (::page-id ctx)
                          (::rotation ctx))))

(defresource document [web id]
  :allowed-methods #{:get :post}
  :available-media-types +default-media-types+
  :malformed? (fn [ctx]
                (try
                  (case (get-in ctx [:request :request-method])
                    :get [false {::id (Integer/parseInt id)}]
                    :post
                    (let [req (:request ctx)
                          params (:body req)
                          attrs (select-keys params [:title])
                          attrs (into {} (remove (comp nil? val) attrs))]
                      (if (and (every? string? (get-in params [:tags :added]))
                               (every? string? (get-in params [:tags :removed])))
                        [false {::id (Integer/parseInt id)
                                ::attrs attrs
                                ::tags (:tags params)}]
                        true)))
                  (catch NumberFormatException e
                    true)))
  ;; TODO(mu): Need to check for POST too
  :authorized? (auth/authorization-fn web :document ::id)
  :exists? (fn [ctx]
             (when-let [d (m/get-document (:db web) (::id ctx))]
               {::document d}))
  :post-redirect? true
  :location (fn [ctx] (str "/documents/" id))
  :post! (fn [ctx]
           (let [id (::id ctx)
                 attrs (::attrs ctx)
                 {added-tags :added, removed-tags :removed} (::tags ctx)]
             (db/with-transaction [db (:db web)]
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

(defresource document-download [web id]
  :allowed-methods #{:get}
  :available-media-types ["application/pdf"]
  :malformed? (fn [ctx]
                (try [false {::id (Integer/parseInt id)}]
                     (catch NumberFormatException e
                       true)))
  :authorized? (auth/authorization-fn web :document ::id)
  :exists? (fn [ctx]
             (let [db (:db web)
                   id (::id ctx)
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

(defresource inbox [web]
  :allowed-methods #{:get :delete}
  :available-media-types +default-media-types+
  ;; TODO: :authorized?
  :delete! (fn [ctx]
             (when-let [pages (seq (get-in ctx [:request :body]))]
               (m/remove-from-inbox! (:db web) pages)))
  :handle-ok (fn [ctx]
               (let [pages (m/inbox (:db web))]
                 (condp = (get-in ctx [:representation :media-type])
                   "text/html" (html/inbox pages)
                   pages))))

(defresource documents [web]
  :allowed-methods #{:get :post}
  :available-media-types +default-media-types+
  :malformed? (fn [ctx]
                (try
                  (let [db (:db web)]
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
  ;; TODO(mu): Validate POST
  :authorized? (auth/authorization-fn web :documents ::results)
  :post! (fn [{:keys [request, representation] :as ctx}]
           (db/with-transaction [conn (:db web)]
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
                     tagging (get-in web [:config :tagging])]
                 ;; NOTE: auto-tag*! so we don't trigger updates on the notification bus
                 (m/auto-tag*! conn id tagging
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

(defresource tags [web]
  :available-media-types +default-media-types+
  :allowed-methods #{:get}
  ;; TODO(mu): :authorized?
  :handle-ok (fn [ctx]
               (let [tags (m/all-tags (:db web))]
                 (condp = (get-in ctx [:representation :media-type])
                   "text/html" (html/tags (map :name tags))
                   tags))))

(defn handle-get-objects-for-tag [web req tag]
  (db/with-transaction [conn (:db web)]
    (let [[{tag-id :id}] (db/query conn ["SELECT id FROM tags WHERE name = ?" tag])
          files (db/query conn ["SELECT f.id, f.origin, f.name FROM files AS f JOIN file_tags AS ft ON f.id = ft.file WHERE ft.tag = ?" tag-id])
          pages (db/query conn ["SELECT p.id, dp.page FROM pages AS p JOIN document_pages AS dp ON p.id = dp.document JOIN page_tags AS pt ON p.id = pt.page WHERE pt.tag = ?" tag-id])
          documents (db/query conn ["SELECT d.id, d.title FROM documents AS d JOIN document_tags AS dt ON d.id = dt.document WHERE dt.tag = ?" tag-id])]
      {:status 200
       :body (html/objects-for-tag tag files pages documents)})))

(defresource documents-bulk [web]
  :allowed-methods #{:post}
  :available-media-types ["application/json"
                          "application/transit+json"]
  :malformed? (fn [ctx]
                (if-let [ids (seq (get-in ctx [:request :body]))]
                  [false {::ids ids}]
                  [true {::error "Malformed Request Body"}]))
  :handle-malformed ::error
  :authorized? (auth/authorization-fn web :documents ::ids)
  :exists? (fn [ctx]
             (when-let [ids (::ids ctx)]
               {::ids ids
                ::documents (m/get-documents (:db web) ids)}))
  ;; Change the status code to 200
  :as-response (fn [d ctx]
                 (-> (as-response d ctx)
                     (assoc-in [:status] 200)))
  ;; NOTE: We still need to use the created-handler instead of ok
  :handle-created (fn [{documents ::documents}]
                    (zipmap (map :id documents) documents)))

(defn handlers [web]
  (let [web (update-in web [:db] auth/restrict-db auth/null-filter)]
    (routes (GET "/" []
                 (html/root))
            (ANY "/inbox" []
                 (inbox web))
            (ANY "/files/scanner" []
                 (file-scanner web))

            (ANY "/pages/:id/image/:size" [id size]
                 (page-image web id size))
            (ANY "/pages/:id/image" [id]
                 (page-image web id ::max))
            (ANY "/pages/:id/rotation" [id]
                 (page-rotation web id))
            (ANY "/pages/:id" [id]
                 (page web id))

            (ANY "/documents" []
                 (documents web))
            (ANY "/documents/bulk" []
                 (documents-bulk web))
            (ANY "/documents/:id" [id]
                 (document web id))
            (ANY "/documents/:id/download" [id]
                 (document-download web id))

            (ANY "/tags" []
                 (tags web))
            (GET "/tags/:tag" [tag :as req]
                 (handle-get-objects-for-tag web req tag))

            (ANY "/poll" []
                 (partial poll-handler web))

            (route/resources "/")
            (route/not-found "Nothing here"))))

(defn wrap-logging [handler web]
  (if (get-in web [:config :web :log-requests?])
    (fn [req]
      (log/debug web "HTTP Request" req)
      (handler req))
    handler))

(defn wrap-stacktrace
  [handler web]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (log/error web t "Caught exception in Ring")
        {:status 500}))))

(defn make-handlers [web-component]
  (-> (#'handlers web-component)
      #_(auth/wrap-authorization-warnings web-component)
      ;; NOTE: *first* transit, then JSON
      (wrap-transit-body)
      (wrap-params)
      (wrap-logging web-component)
      (wrap-stacktrace web-component)))
