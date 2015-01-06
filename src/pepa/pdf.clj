(ns pepa.pdf
  (:require [clojure.string :as s]
            [clojure.java.io :as io]
            [pepa.util :refer [slurp-bytes run-process with-temp-file]])
  (:import java.lang.ProcessBuilder))

(declare make-reader)

(defmacro with-reader [[name data] & body]
  `(with-temp-file [file# ~data]
     (let [~name (make-reader file#)]
       ~@body)))

;;; PDF Renderer

(defprotocol PDFReader
  (page-count [pdf]
    "Returns the page-count of PDF as a number. Might do IO.")
  (meta-data [pdf]
    "Returns a map of all known metadata-values of PDF. Might do IO.")
  (call-with-rendered-page-file [pdf page file-type dpi f]
    "Renders PAGE (zero-indexed) of PDF using file-type (:png,...)
     into a temporary file and calls f with it.  Does IO.")
  (extract-page-text [pdf page]
    "Extracts text from PAGE (zero-indexed) of PDF"))

(defn ^:private pdf-meta-data [file]
  (let [process (-> ["pdfinfo" (str file)]
                    (ProcessBuilder.)
                    (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                    (.start))
        in (.getInputStream process)
        exit-code (.waitFor process)]
    (if (zero? exit-code)
      (->> (slurp in)
           (s/split-lines)
           (map #(s/split % #"\:\s*" 2))
           (into {}))
      (throw (ex-info "Failed to run subprocess to extract meta-data."
                      {:exit-code exit-code})))))

(defn render-page [pdf page file-type dpi]
  (call-with-rendered-page-file pdf page file-type dpi slurp-bytes))

(deftype PopplerReader [file ^:volatile-mutable meta]
  PDFReader
  (page-count [this]
    (Integer/parseInt (get (meta-data this) "Pages")))
  (meta-data [_] (or meta (set! meta (pdf-meta-data file))))
  (call-with-rendered-page-file [this page file-type dpi f]
    (let [page (inc page) ;; pdftocairo starts at idx 1
          file-type (case file-type
                      ;; NOTE: Always use "." here. See note below.
                      :png "png")
          tmp-file (java.io.File/createTempFile "pdftocairo" (str "." file-type))]
      (try
        (let [args [(str file)
                    (str "-" file-type) ;-png flag
                    "-r" dpi
                    "-f" page
                    "-l" page
                    "-singlefile"
                    ;; NOTE: pdftocairo *always* appends .png to the output path. Strip it here.
                    (let [path (.getAbsolutePath tmp-file)]
                      (subs path 0 (.lastIndexOf path ".")))]
              process (run-process "pdftocairo" args {:type file-type :page page})]
          (f tmp-file))
        (finally
          (.delete tmp-file)))))
  (extract-page-text [this page]
    (assert (< page (page-count this)))
    (let [page (inc page) ;; pdftotext starts at idx 1
          process (run-process "pdftotext" ["-f" page "-l" page file "-"])]
      (slurp (.getInputStream process)))))

(defn poppler-reader [file]
  (PopplerReader. file nil))

(def ^:dynamic *default-reader* poppler-reader)

(defn make-reader [file]
  (*default-reader* file))

;;; PDF Splicing & Merging

(defn extract-page
  "Extracts PAGE (a number) from PDF (input-stream). Returns a file.
  Caller must delete this file when it's no longer needed."
  [file page]
  (let [tmp-file (java.io.File/createTempFile "pdfseparate-" ".pdf")
        page (inc page)
        args ["-f" page
              "-l" page
              file
              (.getAbsolutePath tmp-file)]
        process (-> (into-array (map str (cons "pdfseparate" args)))
                    (ProcessBuilder.)
                    (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                    (.start))
        exit-code (.waitFor process)]
    (when-not (zero? exit-code)
      (.delete tmp-file)
      (throw (ex-info "pdfseparate didn't terminate correctly"
                      {:exit-code exit-code
                       :args args
                       :page page})))
    tmp-file))

(defn split-pdf
  "Extract all pages with numbers in PAGES from PDF. Returns a map
  with page numbers as keys and files as values."
  [pdf pages]
  (with-temp-file [file pdf]
    (let [page-count (page-count (make-reader file))]
      (when-not (< (reduce max pages) page-count)
        (throw (ex-info "Tried to read page not in the PDF."
                        {:page-count page-count
                         :pages pages
                         :pdf file}))))
    ;; We need to force the lazy seq here
    (into {}
          (for [page pages]
            [page (extract-page file page)]))))

(defn merge-pages
  "Merges PAGES into a pdf. PAGES must be a sequence of pdf files. Returns a file."
  [pages]
  (assert (seq pages))
  (let [file (java.io.File/createTempFile "pdfunite-" ".pdf")
        args (concat (map #(.getAbsolutePath %) pages)
                     [(.getAbsolutePath file)])
        process (-> (into-array (map str (cons "pdfunite" args)))
                    (ProcessBuilder.)
                    (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                    (.start))
        exit-code (.waitFor process)]
    (when-not (zero? exit-code)
      (.delete file)
      (throw (ex-info "pdfunit didn't terminate correctly"
                      {:exit-code exit-code
                       :args args
                       :pages pages})))
    file))
