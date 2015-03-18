(ns pepa.logging
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]))

;;; Logger is a special component. Instead of being available via
;;; `component/using' we inject it into every component already in the
;;; system. That wasy ::loggre is available in every component and can
;;; be used like:
;;;
;;; (log/info some-component ...)
;;;
;;; That allows us to log the name of the logging component & access
;;; its state. It also frees us from explicitly declaring ::logger as
;;; a dependency in every component we want to log something.

(defrecord Logger [config]
  component/Lifecycle
  (start [component]
    (println ";; Starting Logger")
    component)

  (stop [component]
    (println ";; Stopping Logger")
    component))

(defn ^:private make-component []
  (map->Logger {}))

(defn wrap-logging [system]
  (let [logger (make-component)
        ;; We can 'inject' ::logger into all components *except* the
        ;; components logger itself depends on.
        all-components (remove (set (keys logger)) (keys system))]
   (-> system
       (component/system-using (zipmap all-components (repeat [::logger])))
       (assoc ::logger (component/using (make-component)
                                        [:config])))))

(defn log [component]
  ;; stub
  )
