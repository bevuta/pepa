(ns pepa.web.notifications
  (:require [com.stuartsierra.component :as component]
            [pepa.bus :as bus]
            [pepa.db :as db]
            [pepa.model :as m]
            [clojure.core.async :as async :refer [<!!]]))

(defrecord Notificator [db bus output])

(defmulti ^:private bus->web
  (fn [notificator bus-message]
    (bus/topic bus-message)))

(defmethod bus->web :default
  [notificator message]
  (println "Got unhandled bus message:" (bus/topic message)))

(defmethod bus->web :pages/new [notificator message]
  (let [db (:db notificator)
        file (:file/id message)]
    ;; TODO(mu): Also add relevant documents
    {:message/topic :pages/new
     :pages (m/page-ids db file)}))

(defmethod bus->web :files/new [notificator message]
  {:message/topic :files/new
   :files (:files message)})

(extend-type Notificator
  component/Lifecycle
  (start [component]
    (println ";; Starting web notificator")
    (let [messages (bus/subscribe-all (:bus component))
          input (async/chan)
          output (async/mult input)]
      (async/thread
        (loop []
          (when-let [message (<!! messages)]
            (when-let [lock ((set db/advisory-locks) (bus/topic message))]
              (db/with-transaction [db (:db component)]
                (db/advisory-xact-lock! db lock)))
            (when-let [web-message (bus->web component message)]
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
