(ns pepa.config
  (:require [com.stuartsierra.component :as component]))

(defrecord Config []
  component/Lifecycle
  (start [component]
    (into component (load-file "config.clj")))

  (stop [component]
    (->Config)))

(defn make-component []
  (map->Config {}))
