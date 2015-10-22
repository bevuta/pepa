(ns pepa.smtp
  (:require [com.stuartsierra.component :as component]
            [pepa.model :as m]
            [pepa.db :as db]
            [pepa.log :as log])
  (:import org.subethamail.smtp.helper.SimpleMessageListener
           org.subethamail.smtp.helper.SimpleMessageListenerAdapter
           org.subethamail.smtp.server.SMTPServer
           java.net.InetAddress))

;; Extracted from https://github.com/ghubber/bote

(defrecord SMTP [config db server]
  component/Lifecycle
  (start [component]
    (if-not (get-in component [:config :smtp :enable])
      component
      (do
        (log/info component "Starting SMTP Server")
        (let [smtp-config (:smtp config)
              host (:host smtp-config)
              port (:port smtp-config)
              address (and host (InetAddress/getByName host))
              server (->
                      (proxy [SimpleMessageListener] []
                        (accept [from to]
                          (log/info component "Accepting mail" (str "(from: " from ", to: " to ")"))
                          true)
                        (deliver [from to data]
                          (db/with-transaction [db (:db component)]
                            ;; TODO: Use proper mail address parser
                            (if-let [files (seq (m/mime-message->files data))]
                              (let [[_ origin] (re-find #"(.+)@" to)
                                    origin (or (and (m/inbox-origin? config origin) origin)
                                               "email")
                                    files (m/store-files! db files {:origin origin})]
                                (when (= origin "email")
                                  (doseq [file files]
                                    (let [id (m/create-document! db {:file (:id file)
                                                                     :title (:name file)})
                                          tagging (get-in component [:config :tagging])]
                                      ;; NOTE: auto-tag*! so we don't
                                      ;; trigger an update on the
                                      ;; notification bus
                                      (log/info component "adding tags to created document")
                                      (m/auto-tag*! db id tagging
                                                    {:origin origin
                                                     :mail/from from
                                                     :mail/to to})))))
                              (log/warn component "Got mail from" from "without attachments")))))
                      (SimpleMessageListenerAdapter.)
                      (SMTPServer.))]
          (doto server
            (.setPort port)
            (.setHostName host)
            (.setBindAddress address)
            (.start))
          (assoc component
                 :server server)))))

  (stop [component]
    (when-let [server (:server component)]
      (log/info component "Stopping SMTP Server")
      (.stop server))
    (assoc component :server nil)))

(defmethod clojure.core/print-method SMTP
  [smtp ^java.io.Writer writer]
  (.write writer (str "#<SMTP Server "
                      (if (:server smtp) "active" "disabled")
                      ">")))

(defn make-component []
  (map->SMTP {}))
