(ns pepa.bus
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async :refer [>!!]]))

;;; pepa.bus is a simple application-wide notification bus. Clients
;;; can subscribe for any topic they want and will receive a value for
;;; any cann of `notify!' for that topic.

(defn subscribe
  "Returns a channel with that will receive a value when `notify!' is
  called for that topic."
  [bus topic]
  (let [chan (async/chan (async/sliding-buffer 1))]
    (async/sub (:output bus) topic chan)
    chan))

;;; TODO: Add optional extra data. Replace `identity' in `async/pub'
;;; below with ::topic or similar and make `notify!' put maps into the
;;; channel.
(defn notify! [bus topic]
  (>!! (:input bus) topic ))

(defrecord Bus [input output]
  component/Lifecycle
  (start [component]
    (println ";; Starting bus")
    (let [input (async/chan)
          output (async/pub input identity)]
      (assoc component
        :input input
        :output output)))

  (stop [component]
    (println ";; Stopping bus")
    (assoc component
      :input nil
      :output nil)))

(defn make-component []
  (map->Bus {}))

