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

(defn notify!
  ([bus topic data]
   (>!! (:input bus) (assoc data ::topic topic)))
  ([bus topic]
   (notify! bus topic {})))

(defrecord Bus [input output]
  component/Lifecycle
  (start [component]
    (println ";; Starting bus")
    (let [input (async/chan)
          output (async/pub input ::topic)]
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

