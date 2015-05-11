(ns pepa.components.document
  (:require [om.core :as om :include-macros true]
            [clojure.string :as s]
            
            [nom.ui :as ui]
            [pepa.navigation :as nav])
  (:import [goog.i18n DateTimeFormat]))

(defmulti ^:private meta-row (fn [kw val] kw))

(defmethod meta-row :default [kw val]
  [(s/capitalize (name kw)) val])

(let [formatter (DateTimeFormat. "dd.MM.yyyy HH:mm")]
  (defn format-date [^Date date]
    (when date
      (.format formatter date))))

(defmethod meta-row :created [kw val]
  [(s/capitalize (name kw)) (format-date val)])
(defmethod meta-row :modified [kw val]
  [(s/capitalize (name kw)) (format-date val)])

(ui/defcomponent meta-table [document]
  (render [_]
    [:ul.meta
     (for [prop [:title :created :modified :creator :size]]
       (let [name (name prop)
             [title value] (meta-row prop (get document prop))]
         [:li {:class name, :key name}
          [:span.title {:key "title"} title]
          [:span.value {:key "value", :title (str value)}
           value]]))]))

