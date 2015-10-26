(ns pepa.components.document
  (:require [om.core :as om :include-macros true]
            [clojure.string :as s]
            [cljs.core.async :refer [<!]]

            [nom.ui :as ui]

            [pepa.api :as api]
            [pepa.data :as data]
            [pepa.components.tags :as tags]
            [pepa.components.editable :as editable]
            [pepa.navigation :as nav]

            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:import [goog.i18n DateTimeFormat DateTimeParse]))

(defmulti meta-title (fn [[prop document] owner opts] prop))
(defmulti meta-value (fn [[prop document] owner opts] prop))

(ui/defcomponentmethod meta-title :default [[prop _] _ _]
  (render [_]
    [:span.title {:key "title"}
     (s/capitalize (name prop))]))

(ui/defcomponentmethod meta-value :default [[key document] _ _]
  (render [_]
    (let [val (get document key)]
     [:span.value {:key "value", :title (str val)}
      val])))

(ui/defcomponentmethod meta-value :title [[key document] _ _]
  (render [_]
    (let [val (get document key)]
      [:span.value {:key "value", :title (str val)}
       (editable/editable-title val
                                (fn [title]
                                  (api/update-document!
                                   (assoc document :title (or title ""))))
                                false)])))

(let [formatter (DateTimeFormat. "dd.MM.yyyy HH:mm")]
  (defn format-datetime [^Date date]
    (when date
      (.format formatter date))))

(ui/defcomponentmethod meta-value :created [[key document] _ _]
  (render [_]
    (let [value (format-datetime (get document key))]
      [:span.value {:key "value", :title value}
       value])))

(ui/defcomponentmethod meta-value :modified [[key document] _ _]
  (render [_]
    (let [value (format-datetime (get document key))]
      [:span.value {:key "value", :title value}
       value])))

;;; Document Date

(let [parser (DateTimeParse. "yyyy-MM-dd")]
  (defn- string->date [date-string]
    (when-not (s/blank? date-string)
      (let [date (js/Date.)]
        (.parse parser date-string date)
        date))))

(let [formatter (DateTimeFormat. "yyyy-MM-dd")]
  (defn- date->date-picker-value [^Date date]
    (.format formatter date)))

(defn ^:private store-document-date! [document owner date]
  (async/take! (api/update-document! (assoc @document :document-date date))
               (fn [value]
                 (om/set-state! owner :editing? false))))

(defn ^:private date-input-supported? []
  (-> (doto (js/document.createElement "input")
        (.setAttribute "type" "date"))
      (.-type)
      (= "date")))

(let [formatter (DateTimeFormat. "yyyy-MM-dd")]
  (defn ^:private format-date [^Date date]
    (when date
      (.format formatter date))))

(ui/defcomponentmethod meta-title :document-date [_ _ _]
  (render [_]
    [:span.title {:key "title"} "Date"]))

(ui/defcomponentmethod meta-value :document-date [[_ document] owner _]
  (init-state [_]
    {:editing? false})
  (render-state [_ {:keys [editing?]}]
    (let [supported? (date-input-supported?)
          document-date (:document-date document)
          str-value (when document-date (format-date document-date))]
      (if-not editing?
        [:span.value.editable {:key "value", :title str-value
                               :on-click (fn [e]
                                           (when supported?
                                             (om/set-state! owner :editing? true)))}
         (or str-value
             (when supported? "Click to set"))]
        (let [on-submit! (fn [e]
                           (ui/cancel-event e)
                           (->> (om/get-node owner "date-input")
                                (.-value)
                                (string->date)
                                (store-document-date! document owner)))]
          [:form {:on-submit on-submit!}
           [:input {:type "date"
                    :key "date-input"
                    :ref "date-input"
                    :default-value (when document-date
                                     (date->date-picker-value document-date))
                    :on-blur on-submit!}]])))))

;;; Meta Table Rows

(ui/defcomponent multi-meta-item [prop owner _]
  (render [_]
    (let [name (name prop)]
      [:li {:class name, :key name}
       (om/build meta-title [prop "Multiple"])
       (om/build meta-value [:default {:default "Multiple"}])])))

(ui/defcomponent meta-item [[prop document] owner _]
  (render-state [_ state]
    (let [name (name prop)]
      [:li {:class name, :key name}
       (om/build meta-title [prop document])
       (om/build meta-value [prop document]
                 {:state state})])))

(ui/defcomponent meta-table [documents]
  (render [_]
    (let [single? (= 1 (count documents))
          document (when single? (first documents))]
      [:ul.meta
       (for [prop [:title :created :modified :document-date ]] ;:creator :size
         (if single?
           (om/build meta-item [prop document])
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
          ;; Deref to get the 'actual' value (might be stale)
          (doseq [document documents]
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
          [:button.edit {:on-click #(->> (nav/edit-document-route (first documents))
                                         (nav/navigate!))
                         :disabled (not single?)}
           "Edit in Inbox"]])])))
