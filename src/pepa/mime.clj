(ns pepa.mime
  (:require [pepa.util :refer [slurp-bytes]])
  (:import javax.mail.internet.MimeMessage
           javax.mail.internet.MimeMultipart
           javax.mail.Session
           java.util.Properties))

(defn parse-message [input]
  (let [session (Session/getDefaultInstance (Properties.))]
    (MimeMessage. session input)))

(defn message-part->map [part]
  {:content-type (.getContentType part)
   :filename (.getFileName part)
   :content (delay (.getContent part))})

(def multipart?
  (partial instance? MimeMultipart))

(defn content->parts [content]
  (let [cnt (.getCount content)]
    (letfn [(next-part [i]
              (when (< i cnt)
                (lazy-seq
                 (let [part (.getBodyPart content i)
                       part-content (.getContent part)
                       next (next-part (inc i))]
                   (if (multipart? part-content)
                     (concat (content->parts part-content) next)
                     (cons (message-part->map part) next))))))]
      (next-part 0))))

(defn message-parts [input]
  (let [msg (parse-message input)
        content (.getContent msg)]
    (if (multipart? content)
      (content->parts content)
      [(message-part->map msg)])))


(defn pdf-part? [part]
  (or (.startsWith (.toLowerCase (str (:content-type part))) "application/pdf")
      (.endsWith (.toLowerCase (str (:filename part))) ".pdf")))
