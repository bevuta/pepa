(ns pepa.web.poll
  (:require [pepa.model :as m]
            [pepa.db :as db]

            [clojure.data.json :as json]
            [cognitect.transit :as transit]
            
            [immutant.web.async :as async-web]
            [clojure.core.async :as async :refer [go <!]])
  (:import java.io.ByteArrayOutputStream))

(defn ^:private data->response-fn [content-type]
  (case content-type
    "application/json"
    json/write-str

    "application/transit+json"
    (let [out (ByteArrayOutputStream.)
          writer (transit/writer out :json)]
      (fn [data]
        (.reset out)
        (transit/write writer data)
        (.toString out "UTF-8")))))

(defn ^:private send-fn [content-type]
  (let [data->response (data->response-fn content-type)]
    (fn send! [ch data]
      (async-web/send! ch
                       (-> data (data->response) (str \newline))
                       {:close? true}))))

(defn ^:private send-seqs! [db ch content-type]
  ((send-fn content-type) ch {:seqs (m/sequence-numbers db)}))

(defn ^:private handle-poll! [db ch seqs content-type]
  (let [send! (send-fn content-type)]
    (go
      (<! (async/timeout 1000))
      (send! ch {:foo 42}))))

(defn ^:private poll-handler* [req]
  (let [method (:request-method req)
        allowed-methods #{:get :post}
        content-type (soome #(re-find % (:content-type req))
                            [#"^application/transit\+json"
                             #"^application/json"])]
    (cond
      (not content-type)
      {:status 406}

      (not (contains? allowed-methods method))
      {:status 405}

      true
      (-> req
          (async-web/as-channel
           {:on-open (fn [ch]
                       (let [seqs (:body req)
                             db (:pepa.web.handlers/db req)]
                         (if (empty? seqs)
                           (send-seqs! db ch content-type)
                           (handle-poll! db
                                         ch
                                         seqs
                                         content-type))))
            :on-error (fn [ch throwable]
                        (println "Caught exception:" throwable)
                        (async-web/close ch))})
          (assoc-in [:headers "content-type"] content-type)))))


(def poll-handler #'poll-handler*)
