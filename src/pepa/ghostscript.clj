(ns pepa.ghostscript
  (:require [clojure.string :as s]
            [clojure.java.io :as io]
            [pepa.util :refer [slurp-bytes run-process with-temp-file]])
  (:import java.lang.ProcessBuilder
           java.io.File
           java.io.BufferedInputStream))

(defn ps->pdf [input]
  (let [tmp-file (File/createTempFile "ps2pdf-output" ".pdf")
        process (-> ["ps2pdf" "-" (str tmp-file)]
                    (ProcessBuilder.)
                    (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                    (.start))
        process-input (.getOutputStream process)]
    (io/copy (io/input-stream input) process-input)
    (.close process-input)
    (let [exit-code (.waitFor process)]
      (if (zero? exit-code)
        tmp-file
        (throw (ex-info "Failed to run subprocess to extract meta-data."
                        {:exit-code exit-code}))))))


