(ns pepa.model.route
  (:require [clojure.spec :as s]))

(s/def ::route-params map?)
(s/def ::handler      keyword?)
