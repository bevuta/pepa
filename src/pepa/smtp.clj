(ns pepa.smtp
  (:require [com.stuartsierra.component :as component]
            [pepa.model :as m]
            [pepa.db :as db])
  (:import org.subethamail.smtp.helper.SimpleMessageListener
           org.subethamail.smtp.helper.SimpleMessageListenerAdapter
           org.subethamail.smtp.server.SMTPServer
           java.net.InetAddress))

;; Extracted from https://github.com/ghubber/bote

(defrecord SMTP [config db server]
  component/Lifecycle
  (start [component]
    (when (get-in component [:config :smtp :enable])
      (println ";; Starting SMTP server")
      (let [config (:smtp config)
            host (:host config)
            port (:port config)
            address (and host (InetAddress/getByName host))
            server (->
                    (proxy [SimpleMessageListener] []
                      (accept [from to] true)
                      (deliver [from to data]
                        (db/with-transaction [db (:db component)]
                          ;; TODO: Use proper mail address parser
                          (let [origin (if (.startsWith to "scanner@") "scanner" "email")
                                files (m/store-files! db
                                                      (m/mime-message->files data)
                                                      {:origin origin})]
                            (when (= origin "email")
                              (doseq [file files]
                                (let [id (m/create-document! db {:file (:id file)
                                                                 :title (:name file)})
                                      tagging (get-in component [:config :tagging])]
                                  (m/auto-tag! db id tagging
                                               {:origin origin
                                                :mail/from from
                                                :mail/to to}))))))))
                    (SimpleMessageListenerAdapter.)
                    (SMTPServer.))]
        (doto server
          (.setPort port)
          (.setHostName host)
          (.setBindAddress address)
          (.start))
        (assoc component
               :server server))))

  (stop [component]
    (println ";; Stopping SMTP server")
    (.stop server)
    (assoc component :server nil)))

(defmethod clojure.core/print-method SMTP
  [smtp ^java.io.Writer writer]
  (.write writer (str "#<SMTP Server "
                      (if (:server smtp) "active" "disabled")
                      ">")))

(defn make-component []
  (map->SMTP {}))
