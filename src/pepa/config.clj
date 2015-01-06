(ns pepa.config
  (:require [com.stuartsierra.component :as component]))

(defrecord Config []
  component/Lifecycle
  (start [component]
    (println ";; Loading config")
    (into component (load-file "config.clj")))

  (stop [component]
    (println ";; Clearing config")
    (->Config)))

(defn make-component []
  (map->Config {}))
