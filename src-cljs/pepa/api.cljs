(ns pepa.api
  (:require [cognitect.transit :as transit]
            [cljs.core.async :as async :refer [<!]]

            [clojure.string :as s]
            [goog.string :as gstring]

            [om.core :as om]
            [pepa.data :as data]

            [clojure.browser.event :as event])
  (:import [goog.net XhrIo XmlHttp XmlHttpFactory EventType])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def +xhr-timeout+ (* 5 1000))

(let [reader (transit/reader :json)]
  (defn ^:private parse-xhr-response
  "Takes a finished googl.net.XhrIo, returns a map with :status, :response/text, etc."
  [xhr]
  (let [text (.getResponseText xhr)
        transit (try
                  ;; Only parse body when it's transit
                  (when (and (seq text)
                             (re-find #"application/transit\+json"
                                      (or (.getResponseHeader xhr "content-type") "")))
                    (transit/read reader text))
                  (catch js/Error e
                    (js/console.error "Couldn't parse Transit" text)
                    nil))]
    {:status (.getStatus xhr)
     :response/transit transit
     :response/text (when-not transit text)
     :successful? (.isSuccess xhr)})))


;;; HACK: Because the current release of Google Closure doesn't
;;; include support for the 'progress' event of Xhr, we have to attach
;;; events to the underlying native object. But we have to do this
;;; *before* Closure calls .open() on the object or else we won't
;;; receive any events. Because there's no way to get the Xhr before
;;; xhr.send(), we create a XmlHttpFactory dummy which always returns
;;; the same object. We can then hook up event handlers to the Xhr
;;; returned by it and therefore receive progress events.

(defn ^:private xhr-factory-dummy
  "Returns a goog.net.XmlHttpFactory fake which returns always the
  same Xhr."
  []
  (let [xhr (XmlHttp.)
        obj (XmlHttpFactory.)]
    (set! (.-createInstance obj) (constantly xhr))
    (set! (.-getOptions obj) (constantly #js {}))
    obj))

(let [writer (transit/writer :json)]
  (defn xhr-request!
  "Performs an XmlHttpRequest to uri using method and payload data.

  Returns a channel containing something."
  ([uri method content-type data timeout & [progress]]
   {:pre [(string? uri)
          (keyword? method)
          (string? content-type)]}
   (let [ch (async/chan)
         factory (xhr-factory-dummy)
         xhr (XhrIo. factory)]
     ;; Request load/error/abort requests
     (let [upload (.-upload (.createInstance factory))
           put! (fn [d] (when progress (async/put! progress d)))]
       (doseq [[type f] [[EventType.PROGRESS #(put! (/ (.-loaded %) (.-total %)))]
                         [EventType.LOAD     #(put! :loaded)]
                         [EventType.ERROR    #(put! :error)]
                         [EventType.ABORT    #(put! :abort)]]]
         (.addEventListener upload type f)))
     (doto xhr
       (.setTimeoutInterval timeout)
       (event/listen EventType.COMPLETE
                     (fn [e]
                       (when progress (async/close! progress))
                       (async/put! ch (parse-xhr-response e.target))))
       (.send uri
              (s/upper-case (name method))
              (if (= "application/transit+json" content-type)
                (transit/write writer data)
                data)
              (clj->js
               (merge {"Accept" "application/transit+json"}
                      (when data {"Content-Type" (when data content-type)}))) ))
     ch))
  ([uri method content-type data]
   (xhr-request! uri method content-type data +xhr-timeout+))
  ([uri method data]
   (xhr-request! uri method "application/transit+json" data))
  ([uri method]
   (xhr-request! uri method nil))))

(defn fetch-document-ids
  "Fetches all document-ids from the server."
  []
  (go
    (some-> (xhr-request! "/documents" :get)
            (<!)
            :response/transit)))

(defn search-documents [query]
  (go
    (assert (seq query))
    (let [url (str "documents?q=" (gstring/urlEncode query))]
      (some-> (xhr-request! url :get)
              (<!)
              :response/transit))))

(defn ^:private db-document->Document [document]
  (-> document
      (data/map->Document)
      (update-in [:pages] #(vec (map data/map->Page %)))
      (update-in [:tags] vec)))

(defn fetch-document
  "Fetches document with ID and all its pages. Returns a channel which
  will eventually contain the document (or nil in case of error)."
  [id]
  (go
    (some-> (-> (str "/documents/" id)
                (xhr-request! :get)
                (<!)
                :response/transit)
            (db-document->Document))))

(defn fetch-documents!
  "Fetches all documents in IDs and stores them in the application
  state."
  [ids]
  (go
    (let [documents (->> ids
                         (xhr-request! "/documents/bulk" :post
                                       "application/transit+json")
                         (<!)
                         (:response/transit))]
      (doseq [id ids]
        (if-let [document (some->
                           (get documents id)
                           (db-document->Document)
                           (with-meta {:last-update (js/Date.)}))]
          (om/update! (om/root-cursor data/state) [:documents id]
                      document)
          (js/console.warn "Failed to fetch document for ID" id))))))

(defn fetch-inbox []
  (go
    (vec (:response/transit (<! (xhr-request! "/inbox" :get))))))

(defn fetch-inbox! []
  (go (om/update! (om/root-cursor data/state)
                  [:inbox :pages]
                  (<! (fetch-inbox)))))

(defn delete-from-inbox! [pages]
  (go
    (let [res (<! (xhr-request! "/inbox" :delete (map :id pages)))]
      (:successful? res))))

(defn ^:private document->api-document [document]
  (-> document
      om/value
      (dissoc :id)
      (assoc :pages (mapv :id (:pages document)))
      (update-in [:tags] vec)
      (update-in [:title] str)))

(defn save-new-document!
  "Saves DOCUMENT. Returns a channel. Channel will contain the saved
  document in case of success, nil in case of error."
  ([document origin]
   (go
     (when-let [res (<! (xhr-request! "/documents" :post
                                      (assoc (document->api-document document)
                                             :origin origin)))]
       (when (:successful? res)
         (let [document (-> (:response/transit res)
                            (db-document->Document))]
           (om/update! (om/root-cursor data/state)
                       [:documents (:id document)]
                       document)
           document)))))
  ([document]
   (save-new-document! document "web")))

(defn update-document!
  "Diffs the document with the same id on the server with DOCUMENT and
  updates it to match DOCUMENT. Will also update the application
  state (whether DOCUMENT is a cursor or not)."
  [document]
  (go
    (println "saving document" (:id document))
    ;; TODO: Stop fetching the document here
    (let [server (<! (fetch-document (:id document)))
          title (when-not (= (:title document)
                             (:title server))
                  (:title document))
          date (not= (:document-date document)
                     (:document-date server))
          tags {:added (remove (set (:tags server)) (:tags document))
                :removed (remove (set (:tags document)) (:tags server))}]
      (when-not server
        (throw (ex-info (str "Couldn't find document with id " (:id document))
                        {:document document
                         :document/id (:id document)
                         :response server})))
      (if (or title date (seq (:added tags)) (seq (:removed tags)))
        (let [response (<! (xhr-request! (str "/documents/" (:id document))
                                         :post
                                         {:title title, :document-date (:document-date document) :tags tags}))]
          (if (= 200 (:status response))
            (let [new-document (-> response
                                   :response/transit
                                   (db-document->Document))]
              (data/store-document! new-document)
              new-document)
            (throw (ex-info (str "Failed to update document with id " (:id document))
                            {:document (om/value document)
                             :server-document server
                             :title title
                             :changed-tags tags}))))
        document))))

;;; Tag Handling

(defn fetch-tags [& [detailed?]]
  (go
    (let [response (<! (xhr-request!
                        (str "/tags" (when detailed? "?detailed=true"))
                        :get))]
      (when (= 200 (:status response))
        (:response/transit response)))))

(defn ^:private store-tags! [state tags]
  (om/transact! state :tags #(merge % tags)))

(defn fetch-tags! [state]
  (go
    (store-tags! state (<! (fetch-tags true)))))

;;; TODO: We might want to store all document-ids for a tag. That
;;; would allow us to skip querying the server when doing a tag
;;; search. This should work as tags are always up-to-date (via push).
(defn refresh-tag! [state tag]
  (go
    (println "refreshing tag" (pr-str tag))
    (let [response (<! (xhr-request!
                        (str "/tags/" (-> tag name gstring/urlEncode))
                        :get))]
      (when (= 200 (:status response))
        (let [response (:response/transit response)]
          (om/update! state [:tags tag]
                      (-> response :documents count)))))))

;;; Page Rotation

(defn fetch-page [id]
  (go
    (some-> (xhr-request! (str "/pages/" id) :get)
            (<!)
            (:response/transit)
            (data/map->Page))))

(defn rotate-page! [page rotation]
  (go
    (when-not (= rotation (:rotation page))
      (let [rotation (mod rotation 360)
            res (<! (xhr-request! (str "/pages/" (:id page) "/rotation")
                                  :post {:rotation rotation}))]
        (when (and (:successful? res) (om/cursor? page))
          (om/update! page :rotation rotation))))))

;;; Change Handling

(defmulti ^:private entities-changed* (fn [state entity changes]
                                        entity))
(defmethod entities-changed* :default [& _])

(defmethod entities-changed* :documents [state _ changes]
  ;; Only fetch documents with a local copy
  (let [ids (filter #(get-in @state [:documents %])
                    (:documents changes))]
    (fetch-documents! ids)))

(defmethod entities-changed* :inbox [state _ changes]
  (go
    (let [page-ids (:inbox changes)
          pages (mapv fetch-page page-ids)]
      ;; Just overwrite the inbox-stuff for now
      (fetch-inbox!))))

(defmethod entities-changed* :tags [state _ changes]
  (when-let [tags (:tags changes)]
    ;; TODO: Batch this in one request when we have the endpoint
    (doseq [tag tags]
      (refresh-tag! state tag))))

;;; HACK
(defmethod entities-changed* :deletions [state _ changes]
  (js/console.warn "NOT applying (most) deletions: Not implemented")
  (when-let [changed-tags (get-in changes [:deletions :tags])]
    (doseq [tag changed-tags]
      (refresh-tag! state tag))))

(defn entities-changed! [state changes]
  (doseq [entity (keys changes)]
    (entities-changed* state entity changes)))

;;; Polling

(def +poll-timeout+ (* 30 1000))

(defn ^:private poll! [state]
  (go
    (try
      (let [response (<! (xhr-request! "/poll"
                                       :post
                                       "application/transit+json"
                                       (:seqs @state)
                                       +poll-timeout+))]
        (when (:successful? response)
          (let [data (:response/transit response)]
            (println "[poll]" (pr-str (assoc data :seqs '...)))
            (when-let [seqs (:seqs data)]
              (om/transact! state :seqs #(merge % seqs)))
            (entities-changed! state (dissoc data :seqs)))))
      (catch ExceptionInfo e
        (js/console.error "[poll] Caught Exception: " e)))))

(defn start-polling! [state]
  (let [control (async/chan)]
    (go-loop []
      (let [timeout (async/timeout 1000)
            [_ ch] (alts! [control timeout])]
        (when (= ch timeout)
          (<! (poll! state))
          (<! (async/timeout 1000))
          (recur))))
    control))

(defn stop-polling! [control]
  (async/close! control))
