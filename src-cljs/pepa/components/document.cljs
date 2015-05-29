(ns pepa.components.document
  (:require [om.core :as om :include-macros true]
            [clojure.string :as s]
            
            [nom.ui :as ui]

            [pepa.api :as api]
            [pepa.data :as data]
            [pepa.components.tags :as tags]
            [pepa.navigation :as nav]

            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:import [goog.i18n DateTimeFormat]))

(defmulti ^:private meta-row (fn [kw val] kw))

(defmethod meta-row :default [kw val]
  val)

(let [formatter (DateTimeFormat. "dd.MM.yyyy HH:mm")]
  (defn format-date [^Date date]
    (when date
      (.format formatter date))))

(defmethod meta-row :created [kw val]
  (format-date val))
(defmethod meta-row :modified [kw val]
  (format-date val))

(ui/defcomponent meta-table [documents]
  (render [_]
    (let [single? (= 1 (count documents))]
      [:ul.meta
       (for [prop [:title :created :modified :creator :size]]
         (let [name (name prop)
               title (s/capitalize name)
               value (if single?
                       (meta-row prop (get (first documents) prop))
                       "Multiple")]
           [:li {:class name, :key name}
            [:span.title {:key "title"} title]
            [:span.value {:key "value", :title (str value)}
             value]]))])))

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
           "Nukular vernichten"]])])))
