(ns pepa.logging
  (:require [com.stuartsierra.component :as component]
            clojure.tools.logging))

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

(defprotocol ILogger
  (-log [logger component level throwable message]
    "The generic logging function. Takes a ILogger instance, the
    component that initiated the logging, a log-level, a
    throwable (might be nil) and a message."))

(defrecord CTLLogger [config])
(defrecord DummyLogger [config])

(defn make-ctl-logger
  "Creates a logger which logs via clojure.tools.logging."
  []
  (map->CTLLogger {}))

(defn make-dummy-logger
  "Creates a dummy logger which discards all input."
  []
  (map->DummyLogger {}))

(defn wrap-logging
  ([system logger]
   (let [;; We can 'inject' ::logger into all components *except* the
         ;; components logger itself depends on.
         all-components (remove (set (keys logger)) (keys system))]
     (-> system
         (component/system-using (zipmap all-components (repeat [::logger])))
         (assoc ::logger (component/using logger
                                          [:config])))))
  ([system]
   (wrap-logging system (make-ctl-logger))))

(defn log
  ([component level throwable message]
   ;; TODO(mu): Not sure if we should *log* if no logger is available.
   ;; Very philosophical question.
   (when-let [logger (::logger component)]
     (-log logger component level throwable message)))
  ([component level message]
   (log component level nil message)))

(defmacro ^:private defloglevel [level]
  (let [level-kw (keyword level)]
    `(defn ~level
       {:arglists '([~'component ~'throwable ~'message]
                    [~'component ~'message])}
       ([component# throwable# message#]
        (log component# ~level-kw throwable# message#))
       ([component# message#]
        (log component# ~level-kw nil message#)))))

(defloglevel trace)
(defloglevel debug)
(defloglevel info)
(defloglevel warn)
(defloglevel error)
(defloglevel fatal)

;;; Extend Logger 

(extend-type CTLLogger
  ILogger
  (-log [logger component level throwable message]
    (clojure.tools.logging/log (.getName (type component)) level throwable message)))

(extend-type DummyLogger
  ILogger
  (-log [logger component level throwable message]))
