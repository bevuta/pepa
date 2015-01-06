(ns pepa.bus
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async :refer [>!!]]))

(defn subscribe [bus topic]
  (let [chan (async/chan (async/sliding-buffer 1))]
    (async/sub (:output bus) topic chan)
    chan))

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

