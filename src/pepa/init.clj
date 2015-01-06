(ns pepa.init
  (:require [clojure.java.io :as io])
  (:import clojure.lang.ExceptionInfo))

(defn write-sample-config [f]
  (io/copy (io/resource "config.sample.clj") f))

(defn write-db-schema [f]
  (io/copy (io/resource "schema.sql") f))

(defn ^:private write-resource [name path]
  (let [f (io/file path)]
    (if-not (.exists f)
      (with-open [out (io/output-stream f)]
        (io/copy (io/input-stream (io/resource name))
                 out)
        f)
      (println "Output file" path "already exists. Keeping existing file."))))

(defn write-schema []
  (write-resource "schema.sql" "schema.sql"))

(defn write-config []
  (write-resource "config.sample.clj" "config.clj"))
