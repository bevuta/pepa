(ns pepa.systemd
  (:require [com.stuartsierra.component :as component]
            [clojure.string :as s]
            [immutant.scheduling :as sched]

            [pepa.log :as log])
  (:import java.lang.Runtime))

;;; This component will notify systemd about the current status of the
;;; system periodically. If systemd isn't running it will not do
;;; anything.

(defn system-ready! [systemd]
  ;; NOTE: THis all is broken. All hope is lost.
  (->
   (.exec (Runtime/getRuntime)
          (into-array ["systemd-notify"
                       "READY=1"
                       "WATCHDOG=1"]))
   (.waitFor)))

(defn systemd? [systemd]
  (-> (System/getenv "NOTIFY_SOCKET")
      (s/blank?)
      (not)))

(defrecord SystemdNotify [thread]
  component/Lifecycle
  (start [component]
    (if-not (systemd? component)
      component
      (do
        (log/info component "Notifying systemd about startup")
        ;; HACK: Try notifying every few seconds as it isn't reliable
        (let [sched (sched/schedule #(system-ready! component)
                                    (sched/every 5 :seconds))]
          (assoc component :sched sched)))))
  (stop [component]
    (when-let [s (:sched component)]
      (log/info component "Shutting down systemd-notify thread")
      (sched/stop s))
    (assoc component :sched nil)))

(defn make-component []
  (map->SystemdNotify {}))
