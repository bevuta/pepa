(ns pepa.mime
  (:require [pepa.util :refer [slurp-bytes]])
  (:import
   [javax.mail.internet MimeMessage MimeMultipart MimeBodyPart]
   javax.mail.Session
   java.util.Properties))

(defn ^:private parse-message [input]
  (let [session (Session/getDefaultInstance (Properties.))]
    (MimeMessage. session input)))

(defn ^:private message-part->map [part]
  {:content-type (.getContentType part)
   :filename     (.getFileName part)
   :content (delay (.getContent part))})

(def ^:private multipart?
  (partial instance? MimeMultipart))

(def ^:private body-part?
  (partial instance? MimeBodyPart))

(defn ^:private message-part? [part]
  (= "message/rfc822" (.getContentType part)))

(def ^:private message? (partial instance? MimeMessage))

(defn ^:private children? [part]
  (or (multipart? part)
      (message-part? part)
      (message? part)
      (and (body-part? part) (multipart? (.getContent part)))))

(defn ^:private children [part]
  (cond
    (message? part)
    (recur (.getContent part))

    (message-part? part)
    (recur (.getContent part))

    (and (body-part? part) (multipart? (.getContent part)))
    (recur (.getContent part))

    (multipart? part)
    (map #(.getBodyPart part %) (range (.getCount part)))))

(defn ^:private content->parts [content]
  (->>
   (tree-seq children? children content)
   (remove multipart?)
   (map message-part->map)))

(defn ^:private enumeration->map [enumeration]
  (->> enumeration
       enumeration-seq
       (map (fn [x] [(.getName x) (.getValue x)]))
       (into {})))

(defn message-parts+headers [input]
  (let [msg     (parse-message input)
        content (.getContent msg)
        parts   (if (multipart? content)
                  (content->parts content)
                  [(message-part->map msg)])
        headers (enumeration->map (.getAllHeaders msg))]
    [parts headers]))

(defn pdf-part? [part]
  (or (.startsWith (.toLowerCase (str (:content-type part))) "application/pdf")
      (.endsWith (.toLowerCase (str (:filename part))) ".pdf")))
