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

(defmulti meta-value (fn [[kw val] owner opts] kw))

(ui/defcomponentmethod meta-value :default [[key value] _ _]
  (render [_]
    [:span.value {:key "value", :title (str value)}
     value]))

(let [formatter (DateTimeFormat. "dd.MM.yyyy HH:mm")]
  (defn format-datetime [^Date date]
    (when date
      (.format formatter date))))

(let [formatter (DateTimeFormat. "dd.MM.yyyy")]
  (defn ^:private format-date [^Date date]
    (when date
      (.format formatter date))))

(ui/defcomponentmethod meta-value :created [[key date] _ _]
  (render [_]
    (let [value (format-datetime date)]
      [:span.value {:key "value", :title value}
       value])))

(ui/defcomponentmethod meta-value :modified [[key date] _ _]
  (render [_]
    (let [value (format-datetime date)]
      [:span.value {:key "value", :title value}
       value])))

(let [parser (DateTimeParse. "yyyy-MM-dd")]
  (defn string->date [date-string]
    (when date-string
      (let [date (js/Date.)]
        (.parse parser date-string date)
        date))))

(let [formatter (DateTimeFormat. "yyyy-MM-dd")]
  (defn ^:private date->date-picker-value [^Date date]
    (.format formatter date)))

(defn ^:private document-date-changed [owner e]
  (let [value e.currentTarget.value
        date (when-not (s/blank? value) (string->date value))]
    (om/set-state! owner :date date)))

(defn ^:private store-document-date! [owner date e]
  (let [callback (om/get-state owner :change-callback)]
    (when (fn? callback)
      (callback :document-date date))
    (om/set-state! owner :editing? false)))

(ui/defcomponentmethod meta-value :document-date [[_ date] owner _]
  (init-state [_]
    {:editing? false
     :date (om/value date)})
  (render-state [_ {:keys [editing? date change-callback]}]
    (if-not editing?
      (let [value (when date (format-date date))]
        [:span.value {:key "value", :title (str value)
                      :on-click (fn [e] (om/set-state! owner :editing? true))}
         (or value "Click to set")])
      (let [on-submit! (partial store-document-date! owner date)]
        [:form {:on-submit on-submit! :on-blur on-submit!}
         [:input {:type "date"
                  :key "date-input"
                  :on-change (partial document-date-changed owner)
                  :value (when date
                           (date->date-picker-value date))}]]))))

(ui/defcomponent meta-item [[prop value] owner _]
  (render-state [_ state]
    (let [name (name prop)
          title (s/capitalize name)]
      [:li {:class name, :key name}
       [:span.title {:key "title"} title]
       (om/build meta-value [prop value]
                 {:state state})])))

(defn ^:private property-changed! [document property value]
  (assert (contains? #{:title :document-date} property))
  (println "Updating property" property "on document" (:id @document))
  (api/update-document! (assoc @document property value)))

(ui/defcomponent meta-table [document]
  (render [_]
    [:ul.meta
     (for [prop [:title :created :modified :document-date :creator :size]]
       (om/build meta-item [prop (get document prop)]
                 {:state {:change-callback (partial property-changed! document)}}))]))

