(ns pepa.preloader
  (:require [cljs.core.async :as async]
            [clojure.browser.event :as event])
  (:require-macros [pepa.resources :as res]
                   [cljs.core.async.macros :refer [go]])
  (:import goog.net.ImageLoader))

(def +image-pathnames+ (res/image-resources))

(defn preload []
  (go
    (let [loader (ImageLoader.)
          complete (async/chan)]
      (event/listen-once loader "complete" #(async/put! complete loader))
      (doseq [img +image-pathnames+]
        (.addImage loader img img))
      (.start loader)
      (<! complete))))
