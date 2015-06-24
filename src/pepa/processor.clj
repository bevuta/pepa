(ns pepa.processor
  (:require [clojure.core.async :as async :refer [<!!]]
            [pepa.db :as db]
            [pepa.bus :as bus]))

(defrecord Processor [notify-topic notify-chan control-chan worker])

(defprotocol IProcessor
  (next-item [this])
  (process-item [this item]))

(defn ^:private next-item* [component processor]
  (db/with-transaction [db (:db component)]
    (db/advisory-xact-lock! db (:notify-topic processor)))
  (db/query (:db component) (next-item component) :result-set-fn first))

(defn ^:private process-next [component processor]
  (let [{:keys [notify-chan control-chan]} processor]
    (if-let [item (next-item* component processor)]
      (do (process-item component item)
          (async/alts!! [control-chan] :default :continue))
      (async/alts!! [notify-chan control-chan]))))

(defn start [component notify-topic]
  (let [notify-chan (bus/subscribe (:bus component) notify-topic (async/sliding-buffer 1))
        control-chan (async/chan)
        processor (->Processor notify-topic notify-chan control-chan nil)
        worker (async/thread
                 (loop []
                   (case (first (process-next component processor))
                     (:stop nil) :done
                     (recur))))]
    (assoc processor
           :worker worker)))

(defn stop [processor]
  (let [{:keys [notify-chan control-chan worker]} processor]
    (when notify-chan
      (async/close! notify-chan))
    (when control-chan
      (async/close! control-chan))
    (<!! worker)))
