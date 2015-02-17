(ns pepa.db
  (:require [com.stuartsierra.component :as component]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as s]
            [pepa.bus :as bus])
  (:import com.mchange.v2.c3p0.ComboPooledDataSource
           org.postgresql.util.PGobject))

(defrecord Database [config datasource]
  component/Lifecycle
  (start [component]
    (println ";; Starting database")
    (let [spec (:db config)
          cpds (doto (ComboPooledDataSource.)
                 (.setDriverClass "org.postgresql.Driver")
                 ;; TOOD: Proper URL encoding of host and dbname
                 (.setJdbcUrl (str "jdbc:postgresql://" (:host spec) "/" (:dbname spec)))
                 (.setUser (:user spec))
                 (.setPassword (:password spec))
                 ;; TODO: Make configurable
                 (.setMaxIdleTimeExcessConnections (* 30 60))
                 (.setMaxIdleTime (* 3 60 60))
                 (.setCheckoutTimeout (* 5 1000)))]
      (assoc component :datasource cpds)))

  (stop [component]
    (println ";; Stopping database")
    (when datasource
      (println ";; Closing DB connection")
      (.close datasource))
    (assoc component :datasource nil)))

(defmethod clojure.core/print-method Database
  [db ^java.io.Writer writer]
  (.write writer (str "#<DatabaseConnection>")))

(defn make-component []
  (map->Database {}))

(def insert! jdbc/insert!)
(def update! jdbc/update!)
(def delete! jdbc/delete!)
(def query jdbc/query)

(defn insert-coll! [db table coll]
  (when (seq coll)
    (apply insert! db table coll)))

(defmacro with-transaction [[conn db] & body]
  `(jdbc/with-db-transaction [~conn ~db]
     ~@body))

(defn placeholders [coll]
  (apply str (interpose ", " (map (constantly "?") coll))))

(defn sql+placeholders [sql-format-str coll]
  (vec (cons (format sql-format-str (placeholders coll)) coll)))

(def advisory-lock-prefix 1952539)

(def advisory-locks
  [:files/new
   :pages/new])

(defn make-advisory-lock-query-fn [lock-fn]
  (let [statement (format "SELECT %s(?::int, ?::int)" lock-fn)]
    (fn [db lock-name]
      (query db [statement
                 advisory-lock-prefix
                 (.indexOf advisory-locks lock-name)]))))

(def advisory-xact-lock!
  (make-advisory-lock-query-fn "pg_advisory_xact_lock"))

(def advisory-xact-lock-shared!
  (make-advisory-lock-query-fn "pg_advisory_xact_lock_shared"))

(defn notify!
  ([db topic data]
   (advisory-xact-lock! db topic)
   (bus/notify! (:bus db) topic data))
  ([db topic]
   (notify! db topic nil)))

;;; Extend namespaced keywords to map to PostgreSQL enums

(extend-type clojure.lang.Keyword
  jdbc/ISQLValue
  (sql-value [kw]
    (let [ns (-> (namespace kw)
                 (s/replace "-" "_"))
          name (name kw)]
      (doto (PGobject.)
        (.setType ns)
        (.setValue name)))))

(def +schema-enums+
  "A set of all PostgreSQL enums in schema.sql. Used to convert
  enum-values back into Clojure keywords."
  #{"processing_status"})

(extend-type String
  jdbc/IResultSetReadColumn
  (result-set-read-column [val rsmeta idx]
    (let [type (.getColumnTypeName rsmeta idx)]
      (if (contains? +schema-enums+ type)
        (keyword (s/replace type "_" "-")
                 val)
        val))))
