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

(defn spawn-client [db ch seqs content-type]
  (let [send! (send-fn content-type)]
    (go
      (cond
        (empty? seqs)
        (send! ch {:seqs (m/sequence-numbers db)})

        true
        (do
          (<! (async/timeout))
                    (send! ch {:foo 42}))))))

(defn ^:private poll-handler* [req]
  (let [method (:request-method req)
        allowed-methods #{:get :post}
        content-type (get #{"application/transit+json"
                            "application/json"}
                          (:content-type req))]
    (cond
      (not content-type)
      {:status 406}

      (not (contains? allowed-methods method))
      {:status 405}

      true
      (let []
        (-> req
            (async-web/as-channel
             {:on-open (fn [ch]
                         (spawn-client (:pepa.web.handlers/db req)
                                       ch
                                       (:body req)
                                       content-type))
              :on-error (fn [ch throwable]
                          (println "Caught exception:" throwable)
                          (async-web/close ch))})
            (assoc-in [:headers "content-type"] content-type))))))


(def poll-handler #'poll-handler*)
