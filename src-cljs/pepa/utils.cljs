(ns pepa.utils)

(defn string->int [s]
  (assert (string? s))
  (try (js/parseInt s 10)
       (catch js/Error e nil)))
