(ns leiningen.pepa
  (:require [leiningen.pepa.schema :as schema]))

(def tasks
  {"schema" schema/run})

(defn pepa [project & [task & args]]
  (if-let [run-task (get tasks task)]
    (run-task args)
    (binding [*out* *err*]
      (print "Invalid pepa task")
      (when (seq (str task))
        (print (str " '" task "'")))
      (newline)
      (newline)
      (println "Available tasks:")
      (doseq [task (sort (keys tasks))]
        (println " " task)))))
