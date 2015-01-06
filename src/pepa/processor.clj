(ns pepa.processor
  (:require [clojure.core.async :as async :refer [<!!]]
            [pepa.db :as db]
            [pepa.bus :as bus]))

(defrecord Processor [control-chan notify-topic worker])

(defprotocol IProcessor
  (next-item [this])
  (process-item [this item]))

(defn ^:private next-item* [component topic]
  (db/with-transaction [db (:db component)]
    (db/advisory-xact-lock! db topic)
    (db/query db (next-item component) :result-set-fn first)))

(defn start [component notify-topic]
  (let [control-chan (async/chan)
        notify-chan (bus/subscribe (:bus component) notify-topic)
        ;; TODO: Turn into a thread-pool?
        worker (async/thread
                 (loop []
                   (let [action (if-let [item (next-item* component notify-topic)]
                                  (do (process-item component item)
                                      (async/alts!! [control-chan] :default :continue))
                                  (async/alts!! [notify-chan control-chan]))]
                     (case (first action)
                       (:stop nil) :done
                       (recur)))))]
    (->Processor control-chan notify-chan worker)))

(defn stop [processor]
  (let [{:keys [notify-chan control-chan worker]} processor]
    (when control-chan
      (async/close! control-chan))
    (when notify-chan
      (async/close! notify-chan))
    (<!! worker)))
