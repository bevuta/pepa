(ns pepa.config
  (:require [com.stuartsierra.component :as component]))

(defrecord Config []
  component/Lifecycle
  (start [component]
    (let [file (or (System/getenv "PEPA_CONFIG") "config.clj")
          settings (load-file file)]
      (into component settings)))
  (stop [component]
    (->Config)))

(defn make-component []
  (map->Config {}))
