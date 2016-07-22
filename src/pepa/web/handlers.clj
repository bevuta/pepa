(ns pepa.web.handlers
  (:require [pepa.db :as db]
            [pepa.model :as m]
            [pepa.log :as log]
            [pepa.web.html :as html]
            [pepa.web.poll :refer [poll-handler]]

            [pepa.log :as log]
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
            [ring.middleware.json :refer [wrap-json-body]]

            [liberator.core :refer [defresource]]
            [liberator.representation :refer [as-response]]
            io.clojure.liberator-transit
            [cognitect.transit :as transit]

            [immutant.web.async :as async-web]
            [clojure.core.async :as async :refer [go <!]])
  (:import java.io.ByteArrayOutputStream
           java.io.ByteArrayInputStream
           java.io.FileInputStream
           java.net.URLEncoder
           java.sql.SQLException
           clojure.lang.ExceptionInfo))

(def +default-media-types+ ["text/html"
                            "application/json"
                            "application/transit+json"])

(defresource file-scanner
  :allowed-methods #{:post}
  :available-media-types +default-media-types+
  :malformed? (fn [ctx]
                (let [req (:request ctx)
                      {:keys [body content-type]} req]
                  (if-let [files (case (:content-type req)
                                   "message/rfc822"  (m/mime-message->files body)
                                   "application/pdf" [{:data         (slurp-bytes body)
                                                       :name         (get-in req [:headers "x-filename"])
                                                       :content-type content-type}]
                                   false)]
                    [false {::files files}]
                    [true  {::error (str "Unsupported content type: " content-type)}])))
  :handle-malformed ::error
  :handle-ok (fn [ctx]
               "Created")
  :post! (fn [ctx]
           (m/store-files! (get-in ctx [:request :pepa/db])
                           (::files ctx)
                           {:origin "scanner"})))

(defresource page [id]
  :allowed-methods #{:get}
  :available-media-types ["application/transit+json"]
  :malformed? (fn [ctx]
                (try [false {::id (Integer/parseInt id 10)}]
                     (catch NumberFormatException e
                       true)))
  :exists? (fn [ctx]
             (let [[page] (m/get-pages (get-in ctx [:request :pepa/db])
                                       [(::id ctx)])]
               (when page
                 {::page (select-keys page [:id :render-status :rotation])})))
  :handle-ok ::page)

(defresource page-image [id size]
  :allowed-methods #{:get}
  :available-media-types ["image/png"]
  :malformed? (fn [ctx]
                (try [false {::id   (Integer/parseInt id 10)
                             ::size (if (= ::max size)
                                      size
                                      (Integer/parseInt size 10))}]
                     (catch NumberFormatException e
                       true)))
  :exists? (fn [ctx]
             (try
               (let [db (get-in ctx [:request :pepa/db])
                     config (get-in ctx [:request :pepa/config])
                     [{:keys [image hash]}] (if (= size ::max)
                                              (db/query db ["SELECT image, hash FROM page_images WHERE page = ? ORDER BY dpi DESC LIMIT 1"
                                                            (::id ctx)])
                                              (db/query db ["SELECT image, hash FROM page_images WHERE page = ? AND dpi = ?"
                                                            (::id ctx) (::size ctx)]))]
                 (when image
                   {::page (ByteArrayInputStream. image)
                    ::hash hash}))
               (catch NumberFormatException e
                 nil)))
  :etag ::hash
  :handle-ok ::page)

(defresource page-rotation [id]
  :allowed-methods #{:post}
  :available-media-types +default-media-types+
  :malformed? (fn [ctx]
                (try
                  (let [id (Integer/parseInt id 10)
                        rotation (get-in ctx [:request :body :rotation])]
                    (if (and (integer? rotation)
                             (zero? (mod rotation 90)))
                      [false {::rotation (mod rotation 360)
                              ::id id}]
                      [true "Invalid rotation"]))
                  (catch NumberFormatException e
                    [true "Malformed ID"])))
  :exists? (fn [ctx]
             (let [db (get-in ctx [:request :pepa/db])
                   [{:keys [id]}] (db/query db ["SELECT id FROM pages WHERE id = ?" (::id ctx)])]
               (when id {::page-id id})))
  :can-post-to-missing? false

  :post! (fn [ctx]
           (let [db (get-in ctx [:request :pepa/db])
                 id (::page-id ctx)
                 rotation (::rotation ctx)]
             (m/rotate-page db id rotation))))

(let [required #{:title}]
  (defn ^:private sanitize-attrs [comp attrs]
    (into {} (remove (fn [[k v]] (when (and (nil? v) (contains? required k))
                                   (do
                                     (log/warn comp (str "Required attribute is null attr: " k))
                                     true))) attrs))))

(defn ^:private parse-document-post [ctx]
  (when (= :post (get-in ctx [:request :request-method]))
    (let [params (get-in ctx [:request :body])
          attrs (->> (select-keys params [:title :document-date :pages])
                     (sanitize-attrs (get-in ctx [:request :pepa/web])))
          tags (:tags params)]
      (when-not (every? string? (mapcat val tags))
        (throw (ex-info "Tags must be strings" {:tags tags})))
      {::tags tags
       ::attrs attrs})))

(defresource document [id]
  :allowed-methods #{:get :post}
  :available-media-types +default-media-types+
  :malformed? (fn [ctx]
                (try
                  (let [id (Integer/parseInt id 10)
                        post-data (parse-document-post ctx)]
                    [false (merge {::id id} post-data)])
                  (catch NumberFormatException e
                    true)
                  (catch ExceptionInfo e
                    [true {:message (.getMessage e)}])))
  :exists? (fn [ctx]
             (when-let [d (m/get-document (get-in ctx [:request :pepa/db]) (::id ctx))]
               {::document d}))
  :post-redirect? true
  :location (fn [ctx] (str "/documents/" id))
  :post! (fn [ctx]
           (let [id (::id ctx)
                 {added-tags :added, removed-tags :removed} (::tags ctx)]
             (assert (every? string? added-tags))
             (assert (every? string? removed-tags))
             ;; Implement transaction-retries
             (loop [retries 5]
               (or (try
                     (db/with-transaction [db (get-in ctx [:request :pepa/db])]
                       (m/update-document! db id (::attrs ctx) added-tags removed-tags)
                       (log/debug db "Getting document:" (m/get-document db id))
                       {::document (m/get-document db id)})
                     (catch SQLException e
                       (log/warn (get-in ctx [:request :pepa/web]) "SQL Transaction to update document " id " failed."
                                 " Retrying... (" retries "left)")
                       nil))
                   (when (pos? retries)
                     (recur (dec retries)))))))
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
  :malformed? (fn [ctx]
                (try [false {::id (Integer/parseInt id)}]
                     (catch NumberFormatException e
                       true)))
  :exists? (fn [ctx]
             (let [db (get-in ctx [:request :pepa/db])
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
  :allowed-methods #{:get :put :delete}
  :available-media-types +default-media-types+
  :malformed? (fn [ctx]
                (if (contains? #{:put :delete} (get-in ctx [:request :request-method]))
                  (let [pages (seq (get-in ctx [:request :body]))]
                    ;; TODO: Better validation
                    (if (and (seq pages) (every? integer? pages))
                      [false {::pages pages}]
                      true))
                  false))
  :put! (fn [ctx]
          (let [db (get-in ctx [:request :pepa/db])
                pages (::pages ctx)]
            (m/add-to-inbox! db pages)))
  :delete! (fn [ctx]
             (let [db (get-in ctx [:request :pepa/db])
                   pages (::pages ctx)]
               (m/remove-from-inbox! db pages)))
  :handle-ok (fn [ctx]
               (let [pages (m/inbox (get-in ctx [:request :pepa/db]))]
                 (condp = (get-in ctx [:representation :media-type])
                   "text/html" (html/inbox pages)
                   pages))))

(defresource documents
  :allowed-methods #{:get :post}
  :available-media-types +default-media-types+
  :malformed? (fn [ctx]
                (let [web (get-in ctx [:request :pepa/web])
                      db  (get-in ctx [:request :pepa/db])]
                  (try
                    (if-let [query (some-> ctx
                                           (get-in [:request :query-params "q"])
                                           (edn/read-string))]
                      [false {::query query
                              ::results (m/query-documents db query)}]
                      [false {::results (m/query-documents db)}])
                    (catch RuntimeException e
                      (log/warn web "Failed to parse query string" e)
                      [true {::error "Invalid query string"}])
                    (catch SQLException e
                      (log/warn web "Generated SQL query failed" e)
                      [true {::error "Query string generated invalid SQL"}]))))
  :post! (fn [{:keys [request, representation] :as ctx}]
           (db/with-transaction [conn (:pepa/db request)]
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
                     tagging (get-in request [:pepa/config :tagging])]
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

(defresource tags
  :available-media-types +default-media-types+
  :allowed-methods #{:get}
  :handle-ok (fn [ctx]
               (let [db (get-in ctx [:request :pepa/db])
                     detailed? (= "true" (get-in ctx [:request :params "detailed"]))
                     tags (if detailed?
                            (m/tag-document-counts db)
                            (m/all-tags db))]
                 (condp = (get-in ctx [:representation :media-type])
                   "text/html" (html/tags (map :name tags))
                   tags))))

(defresource tag [t]
  :available-media-types +default-media-types+
  :allowed-methods #{:get}
  :handle-ok (fn [ctx]
               {:documents (m/tag-documents (get-in ctx [:request :pepa/db]) (str t))}))

(defresource documents-bulk
  :allowed-methods #{:post}
  :available-media-types ["application/json"
                          "application/transit+json"]
  :exists? (fn [ctx]
             (when-let [ids (get-in ctx [:request :body])]
               {::ids ids
                ::documents (m/get-documents (get-in ctx [:request :pepa/db]) ids)}))
  ;; Change the status code to 200
  :as-response (fn [d ctx]
                 (-> (as-response d ctx)
                     (assoc-in [:status] 200)))
  ;; NOTE: We still need to use the created-handler instead of ok
  :handle-created (fn [{documents ::documents}]
                    (zipmap (map :id documents) documents)))

(def handlers
  (routes (GET "/" [] (html/root))
          (ANY "/inbox" [] inbox)
          (ANY "/files/scanner" [] file-scanner)

          (ANY "/pages/:id/image/:size" [id size]
               (page-image id size))
          (ANY "/pages/:id/image" [id]
               (page-image id ::max))
          (ANY "/pages/:id/rotation" [id]
               (page-rotation id))
          (ANY "/pages/:id" [id]
               (page id))

          (ANY "/documents" [] documents)
          (ANY "/documents/bulk" [] documents-bulk)
          (ANY "/documents/:id" [id]
               (document id))
          (ANY "/documents/:id/download" [id]
               (document-download id))

          (ANY "/tags" [] tags)
          (ANY "/tags/:t" [t] (tag t))

          (ANY "/poll" [] poll-handler)

          (route/resources "/")
          (route/not-found "Nothing here")))

(defn ^:private transit-request? [req]
  (let [type (:content-type req)
        [_ flavor] (when type (re-find #"^application/transit\+(json|msgpack)" type))]
    (and flavor (keyword flavor))))

(defn ^:private wrap-transit-body
  "Ring middleware that parses application/transit bodies. Returns a
  400-response if parsing fails."
  [handler]
  (fn [req]
    (if-let [type (transit-request? req)]
      (let [body (:body req)
            reader (transit/reader body type)]
        (try
          (handler (assoc req :body (transit/read reader)))
          (catch Exception ex
            ;; TODO: We might want to log such errors.
            {:status 400
             :headers {:content-type "text/plain"}
             :body "Malformed request."})))
      (handler req))))

(defn wrap-request-logging [handler]
  (fn [req]
    (when (get-in req [:pepa/config :web :log-requests?])
      (pprint req))
    (handler req)))

(defn wrap-exceptions [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        (let [trace? (get-in req [:pepa/config :web :exception-traces?])]
          {:status 500
           :body (html5
                  [:p "Exception caught: "]
                  [:pre
                   (if-not trace?
                     e
                     (with-out-str
                       (.printStackTrace e (java.io.PrintWriter. *out*))))])})))))

(defn wrap-component [handler {:keys [config db bus] :as web}]
  (fn [req]
    (handler (assoc req
                    :pepa/web web
                    :pepa/config config
                    :pepa/db db
                    :pepa/bus bus))))

(defn make-handlers [web-component]
  (-> #'handlers
      ;; NOTE: *first* transit, then JSON
      (wrap-transit-body)
      (wrap-params)
      (wrap-request-logging)
      (wrap-exceptions)
      (wrap-component web-component)))
