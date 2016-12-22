(ns pepa.model.route
  (:require [clojure.spec :as s]))

(s/def ::query-params map?)
(s/def ::handler      keyword?)
