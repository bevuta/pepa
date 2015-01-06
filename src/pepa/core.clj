(ns pepa.core
  (:require [com.stuartsierra.component :as component]
            [pepa.config :as config]
            [pepa.db :as db]
            [pepa.bus :as bus]
            [pepa.web :as web]
            [pepa.smtp :as smtp]
            [pepa.processor.file-page-extractor :as fpe]
            [pepa.processor.page-ocr :as page-ocr])
  (:gen-class))

(defn make-system []
  (component/system-map
   :config (config/make-component)
   :bus (bus/make-component)
   :db (component/using
         (db/make-component)
         [:config :bus])
   :file-page-extractor (component/using
                          (fpe/make-component)
                          [:config :db :bus])
   :page-ocr (component/using
               (page-ocr/make-component)
               [:config :db :bus])
   :web (component/using
          (web/make-component)
          [:config :db :file-page-extractor])
   :smtp (component/using
           (smtp/make-component)
           [:config :db])))

(defn -main [& args]
  (component/start (make-system)))
