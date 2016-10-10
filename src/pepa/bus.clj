(ns pepa.bus
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async :refer [>!!]]

            [pepa.log :as log]))

;;; pepa.bus is a simple application-wide notification bus. Clients
;;; can subscribe for any topic they want and will receive a value for
;;; any call of `notify!' for that topic.

(defn subscribe
  "Returns a channel with that will receive a value when `notify!' is
  called for that topic."
  ([bus topic buf]
   (log/debug "subscribe" {:topic topic :buf/class (class buf)})
   (let [chan (async/chan buf)]
     (async/sub (:output bus) topic chan)
     chan))
  ([bus topic]
   (subscribe bus topic nil)))

(defn notify!
  ([bus topic data]
   (log/debug "notify!" {:topic topic :data data})
   (>!! (:input bus) (assoc data ::topic topic)))
  ([bus topic]
   (notify! bus topic {})))

(defn subscribe-all
  "Returns a channel receiving all messages sent over the bus."
  ([bus buf]
   (log/debug "subscribe-all" {:buf/class (class buf)})
   (let [ch (async/chan buf)]
     (async/tap (:mult bus) ch)))
  ([bus]
   (subscribe-all bus nil)))

(defn topic [message]
  (::topic message))

(defrecord Bus [input mult output]
  component/Lifecycle
  (start [component]
    (log/info "Starting Bus")
    (let [input (async/chan)
          mult (async/mult input)
          output-tap (async/chan)
          output (async/pub output-tap ::topic)]
      (async/tap mult output-tap)
      (assoc component
             :input input
             :mult mult
             :output output)))

  (stop [component]
    (log/info "Stopping Bus")
    (when-let [input (:input component)]
      (async/close! input))
    (assoc component
           :input nil
           :mult nil
           :output nil)))

(defn make-component []
  (map->Bus {}))
