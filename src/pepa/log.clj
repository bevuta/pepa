(ns pepa.log
  (:require [com.stuartsierra.component :as component]
            clojure.tools.logging))

;;; Logger is a special component. Instead of being available via
;;; `component/using' we automatically inject it into every component
;;; in the system. That wasy ::logger is available everywhere and can
;;; be used like:
;;;
;;; (log/info some-component ...)
;;;
;;; That allows us to log the name of the logging component & access
;;; its state. It also frees us from explicitly declaring ::logger as
;;; a dependency of every component from which we want to log
;;; something.

(defprotocol ILogger
  (-log
    [logger component level throwable message]
    [logger component level message]
    "The generic logging function. Takes a ILogger instance, the
    component that initiated the logging, a log-level, an optional
    throwable and a message."))

(defrecord CTLLogger [config])

(defn make-ctl-logger
  "Creates a logger which logs via clojure.tools.logging."
  []
  (map->CTLLogger {}))

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
  ([component level & args]
   (if-let [logger (::logger component)]
     (let [-log (partial -log logger component level)]
       (if (instance? Throwable (first args))
         (-log (first args) (apply print-str (rest args)))
         (-log (apply print-str args))))
     (throw (ex-info "Couldn't get ::logger from component"
                     {:component component
                      :level level
                      :args args})))))

(defmacro ^:private defloglevel [level]
  (let [level-kw (keyword level)]
    `(defn ~level
       {:arglists '([~'component ~'throwable ~'& ~'msgs]
                    [~'component ~'& ~'msgs])}
       ([component# & more#]
        (apply log component# ~level-kw more#)))))

(defloglevel trace)
(defloglevel debug)
(defloglevel info)
(defloglevel warn)
(defloglevel error)
(defloglevel fatal)

;;; Extend Logger 

(defn ^:private component-name [c]
  (some-> c class .getSimpleName))

(extend-type CTLLogger
  ILogger
  (-log
    ([logger component level throwable message]
     (clojure.tools.logging/log (component-name component) level throwable message))
    ([logger component level message]
     (clojure.tools.logging/log (component-name component) level nil message))))


