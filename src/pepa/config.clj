(ns pepa.config
  (:require [com.stuartsierra.component :as component]))

(defrecord Config []
  component/Lifecycle
  (start [component]
    (into component (let [file (or (System/getenv "PEPA_CONFIG")
                                   "config.clj")]
                      (load-file file))))

  (stop [component]
    (->Config)))

(defn make-component []
  (map->Config {}))
