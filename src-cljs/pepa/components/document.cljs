(ns pepa.components.document
  (:require [om.core :as om :include-macros true]
            [clojure.string :as s]
            [cljs.core.async :refer [<!]]

            [pepa.api :as api]
            
            [nom.ui :as ui]
            [pepa.navigation :as nav]
            [goog.events.KeyCodes :as keycodes])
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:import [goog.i18n DateTimeFormat DateTimeParse]))

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

(let [formatter (DateTimeFormat. "dd.MM.yyyy")]
  (defmethod meta-row :document-date [kw val]
    [(s/replace (s/capitalize (name kw)) #"-" " ") (if val
                                                     (.format formatter val)
                                                     "Click here to set a date")]))

(let [formatter (DateTimeFormat. "yyyy-MM-dd")]
  (defn ^:private format-datepicker [date]
    (when date
      (.format formatter date))))

(let [parser (DateTimeParse. "yyyy-MM-dd")]
  (defn string->date [date-string]
    (when date-string
      (let [date (js/Date.)]
        (.parse parser date-string date)
        date))))

(defn ^:private property-changed [document prop value]
  (go
    (<! (api/update-document! (assoc @document prop value)))))

(ui/defcomponent meta-item [{:keys [name title value]} _]
  (render [_]
    [:li {:class name, :key name}
     [:span.title {:key "title"} title]
     [:span.value {:key "value", :title (str value)}
      value]]))

(ui/defcomponent ^:private date-picker [{:keys [name title value]}
                                        owner
                                        {:keys [update-fn]}]
  (init-state [_]
    {:editing? false})
  (render-state [_ {:keys [editing? val]}]
    [:li {:class name
          :key name
          :on-click #(om/set-state! owner :editing? true)}
     [:span.title {:key "title"} title]
     (if-not editing?
       [:span.value {:key "value", :title (str value)} value]
       [:form {:on-key-down (fn [e]
                              (when (= keycodes/ENTER e.keyCode)
                                (om/set-state! owner :editing? false)
                                (update-fn :document-date (string->date val))))
               :on-change (fn [e] (om/set-state! owner :val e.target.value))
               :on-blur (fn [e] (do
                                  (om/set-state! owner :editing? false)
                                  (update-fn :document-date (string->date val))))}
        [:input {:type "date"
                 :key "value"
                 :value val}]
        [:input {:type "button"
                 :value "Reset"
                 :on-click #(do
                              (om/set-state! owner :val nil)
                              (update-fn :document-date nil))}]])]))

(ui/defcomponent meta-table [document]
  (render [_]
    [:ul.meta
     (for [prop [:title :created :modified :document-date :creator :size]]
       (let [name (name prop)
             [title value] (meta-row prop (get document prop))
             data {:name name
                   :title title
                   :value value}]
         (if-not (= name "document-date")
           (om/build meta-item data)
           (om/build date-picker
                     data
                     {:state
                      {:val (format-datepicker (prop document))}
                      :opts
                      {:update-fn (partial property-changed document)}}))))]))

