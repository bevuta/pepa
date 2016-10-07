(ns pepa.smtp
  (:require [com.stuartsierra.component :as component]
            [pepa.model :as m]
            [pepa.db :as db]
            [pepa.log :as log]
            [clojure.string :as s])
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
        (log/info "Starting SMTP Server")
        (let [smtp-config (:smtp config)
              host (:host smtp-config)
              port (:port smtp-config)
              address (and host (InetAddress/getByName host))
              server (->
                      (proxy [SimpleMessageListener] []
                        (accept [from to]
                          (log/info "Accepting mail" (str "(from: " from ", to: " to ")"))
                          true)
                        (deliver [from to data]
                          (db/with-transaction [db (:db component)]
                            ;; TODO: Use proper mail address parser
                            (let [[files subject] (m/mime-message->files+subject data)]
                              (if-not files
                                (log/warn "Got mail from" from "without attachments")
                                (let [[_ origin] (re-find #"(.+)@" to)
                                      origin (or (and (m/inbox-origin? config origin) origin)
                                                 "email")
                                      files (m/store-files! db files {:origin origin})]
                                  (when (= origin "email")
                                    (doseq [file files]
                                      (let [title (if-not (s/blank? subject)
                                                    subject
                                                    (:name file))
                                            id (m/create-document! db {:file (:id file)
                                                                       :title title})
                                            tagging (get-in component [:config :tagging])]
                                        ;; NOTE: auto-tag*! so we don't
                                        ;; trigger an update on the
                                        ;; notification bus
                                        (log/info "adding tags to created document")
                                        (m/auto-tag*! db id tagging
                                                      {:origin origin
                                                       :mail/from from
                                                       :mail/to to}))))))))))
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
      (log/info "Stopping SMTP Server")
      (.stop server))
    (assoc component :server nil)))

(defmethod clojure.core/print-method SMTP
  [smtp ^java.io.Writer writer]
  (.write writer (str "#<SMTP Server "
                      (if (:server smtp) "active" "disabled")
                      ">")))

(defn make-component []
  (map->SMTP {}))
