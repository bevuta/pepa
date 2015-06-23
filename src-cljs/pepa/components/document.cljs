(ns pepa.components.document
  (:require [om.core :as om :include-macros true]
            [clojure.string :as s]
            [cljs.core.async :refer [<!]]

            [nom.ui :as ui]

            [pepa.api :as api]
            [pepa.data :as data]
            [pepa.components.tags :as tags]
            [pepa.navigation :as nav]
            [goog.events.KeyCodes :as keycodes]

            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:import [goog.i18n DateTimeFormat DateTimeParse]))

(defmulti meta-title (fn [[prop val] owner opts] prop))
(defmulti meta-value (fn [[prop val] owner opts] prop))

(ui/defcomponentmethod meta-title :default [[prop _] _ _]
  (render [_]
    [:span.title {:key "title"}
     (s/capitalize (name prop))]))

(ui/defcomponentmethod meta-value :default [[key value] _ _]
  (render [_]
    [:span.value {:key "value", :title (str value)}
     value]))

(let [formatter (DateTimeFormat. "dd.MM.yyyy HH:mm")]
  (defn format-datetime [^Date date]
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

;;; Document Date

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

(defn ^:private date-input-supported? []
  (-> (doto (js/document.createElement "input")
        (.setAttribute "type" "date"))
      (.-type)
      (= "date")))

(let [formatter (DateTimeFormat. "dd.MM.yyyy")]
  (defn ^:private format-date [^Date date]
    (when date
      (.format formatter date))))

(ui/defcomponentmethod meta-title :document-date [_ _ _]
  (render [_]
    [:span.title {:key "title"} "Date"]))

(ui/defcomponentmethod meta-value :document-date [[_ date] owner _]
  (init-state [_]
    {:editing? false
     :date (om/value date)})
  (will-receive-props [_ [_ next-date]]
    (when (not= date next-date)
      (om/set-state! owner :date next-date)))
  (render-state [_ {:keys [editing? date change-callback]}]
    (let [supported? (date-input-supported?)
          value (when date (format-date date))]
      (if-not editing?
        [:span.value.editable {:key "value", :title (str value)
                      :on-click (fn [e]
                                  (when supported?
                                    (om/set-state! owner :editing? true)))}
         (or value
             (when supported? "Click to set"))]
        (let [on-submit! (partial store-document-date! owner date)]
          [:form {:on-submit on-submit! :on-blur on-submit!}
           [:input {:type "date"
                    :key "date-input"
                    :on-change (partial document-date-changed owner)
                    :value (when date
                             (date->date-picker-value date))}]])))))

;;; Meta Table Rows

(ui/defcomponent multi-meta-item [prop owner _]
  (render [_]
    (let [name (name prop)]
      [:li {:class name, :key name}
       (om/build meta-title [prop "Multiple"])
       (om/build meta-value [:default "Multiple"])])))

(ui/defcomponent meta-item [[prop value] owner _]
  (render-state [_ state]
    (let [name (name prop)]
      [:li {:class name, :key name}
       (om/build meta-title [prop value])
       (om/build meta-value [prop value]
                 {:state state})])))

;;; TODO: Support mass-edit here
(defn ^:private property-changed! [document property value]
  (assert (contains? #{:title :document-date} property))
  (println "Updating property" property "on document" (:id @document) (str "[" (pr-str value) "]"))
  (api/update-document! (assoc @document property value)))

(ui/defcomponent meta-table [documents]
  (render [_]
    (let [single? (= 1 (count documents))
          document (when single? (first documents))]
      [:ul.meta
       (for [prop [:title :created :modified :document-date ]] ;:creator :size
         (if single?
           (om/build meta-item [prop (get document prop)]
                     {:state {:change-callback (partial property-changed! document)}})
           (om/build multi-meta-item prop)))])))

;;; The usual document sidebar, handling a single or multiple documents

(ui/defcomponent document-sidebar [documents owner _]
  (init-state [_]
    {:tag-changes (async/chan)})
  (will-mount [_]
    (go-loop []
      (when-let [change (<! (om/get-state owner :tag-changes))]
        (prn "got tag change:" change)
        (let [[op tag] change
              documents (om/get-props owner)]
          (prn documents)
          ;; Deref to get the 'actual' value (might be stale)
          (doseq [document documents]
            (prn "updating document" document)
            (-> @document
                (update-in [:tags] (case op
                                     :add data/add-tags
                                     :remove data/remove-tags)
                           [tag])
                (api/update-document!))))
        (recur))))
  (render-state [_ {:keys [tag-changes]}]
    (when (seq documents)
      [:aside
       (om/build meta-table documents)
       [:.fields
        (om/build tags/tags-input documents
                  {:state {:tag-changes tag-changes}})]
       ;; Buttons: Download, Delete, etc.
       (let [single? (= 1 (count documents))]
         [:.buttons
          [:button.download {:on-click #(js/window.open
                                         (str "/documents/" (-> documents first :id) "/download"))
                             :disabled (not single?)}
           "Download"]
          [:button.delete {:disabled true}
           "Delete"]])])))
