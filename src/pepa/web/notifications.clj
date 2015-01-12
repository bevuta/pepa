(ns pepa.web.notifications
  (:require [com.stuartsierra.component :as component]
            [pepa.bus :as bus]
            [pepa.db :as db]
            [pepa.model :as m]
            [pepa.async :refer [collapsing-buffer]]
            [clojure.core.async :as async :refer [<!!]]))

(defrecord Notificator [db bus output])

(defmulti ^:private bus->web
  (fn [notificator bus-message]
    (bus/topic bus-message)))

(defmethod bus->web :default
  [notificator message]
  (println "Got unhandled bus message:" (bus/topic message))
  (prn message))

(defmethod bus->web :pages/new [notificator message]
  (let [db (:db notificator)
        file (:file/id message)]
    ;; TODO(mu): Also add relevant documents
    {:message/topic :pages/new
     :pages (m/page-ids db file)}))

;;; TODO: Auto-dissoc :pepa.bus/topic
(defmethod bus->web :files/new [notificator message]
  (assoc message :message/topic :files/new))

(defmethod bus->web :inbox/new [notificator message]
  (assoc message :message/topic :inbox/new))

(defmethod bus->web :inbox/removed [notificator message]
  (assoc message :message/topic :inbox/removed))

(defmethod bus->web :documents/new [notificator message]
  (assoc message :message/topic :document/new))

(defmethod bus->web :documents/updated [notificator message]
  (assoc message :message/topic :document/updated))

(defmethod bus->web :pages/ocr-updated [notificator message]
  (assoc message :message/topic :pages/ocr-updated))

(extend-type Notificator
  component/Lifecycle
  (start [component]
    (println ";; Starting web notificator")
    (let [messages (bus/subscribe-all (:bus component)
                                      (collapsing-buffer 20 (constantly :resync)))
          input (async/chan)
          output (async/mult input)]
      (async/thread
        (loop []
          (when-let [message (<!! messages)]
            (doseq [lock (if (= :resync message)
                           db/advisory-locks
                           (filter (set db/advisory-locks) [(bus/topic message)]))]
              (db/with-transaction [db (:db component)]
                (db/advisory-xact-lock! db lock)))
            
            (when-let [web-message (if (= :resync message)
                                     {:message/topic :resync}
                                     (some-> (bus->web component message)
                                             (dissoc :pepa.bus/topic)))]
              (println "sending web message..." (pr-str web-message)))
            (recur))))
      (assoc component
             :output output)))

  (stop [component]
    (println ";; Stopping web notificator")
    (async/untap-all (:output component))
    (dissoc component :output)))

(defn make-component []
  (map->Notificator {}))
