(ns pepa.web.notifications
  (:require [com.stuartsierra.component :as component]
            [pepa.bus :as bus]
            [pepa.db :as db]
            [clojure.core.async :as async :refer [<!!]]))

(defrecord Notificator [db bus output])

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
            (println "[notificator] got bus message:" (pr-str message))
            (recur))))
      (assoc component
             :output output)))

  (stop [component]
    (println ";; Stopping web notificator")
    (async/untap-all (:output component))
    (dissoc component :output)))

(defn make-component []
  (map->Notificator {}))
