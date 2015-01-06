(ns pepa.workflows.upload
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [clojure.string :as s]

            [pepa.api.upload :as upload]
            [pepa.data :as data]

            [pepa.navigation :as nav]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn ^:private upload-file! [row owner e]
  (.preventDefault e)
  (when-not (:working? row)
    (go
      (try
        (om/update! row :working? true)
        (let [file (:file row)
              name (.-name file)
              content-type (.-type file)
              document (data/map->Document {:title name})
              upload-blob {:blob (<! (upload/file->u8arr file))
                           :content-type content-type
                           :filename name}
              id (<! (upload/upload-document! document upload-blob))]
          (println "Successfully uploaded document with id" id)
          (om/update! row :document-id id))
        (finally
          (om/update! row :working? false))))))

(defn ^:private byte->kb [byte]
  (Math/floor (/ byte 1024)))

(defn ^:private file-row [row owner _]
  (reify
    om/IRenderState
    (render-state [_ {:keys [hide-fn]}]
      (let [document-id (:document-id row)
            file (:file row)
            name (.-name file)
            size (.-size file)]
        (html
         [:li
          [:form {:on-submit (partial upload-file! row owner)}
           (if document-id
             [:a.title {:href (when document-id
                                (nav/document-route {:id document-id}))
                        :title name}
              name]
             [:span.title {:title name} name])
           [:span.size (str (byte->kb size) "kB")]
           (if-not document-id
             [:button.upload {:type :submit
                              :disabled (:working? row)}
              "Upload"]
             [:.hide {:on-click (fn [e]
                                  (when (fn? hide-fn)
                                    (hide-fn (om/value row)))
                                  (doto e
                                    (.preventDefault)
                                    (.stopPropagation)))}
              "Hide"])]])))))

(defn ^:private remove-file! [files file]
  (om/transact! files (fn [files]
                        (->> files
                             (remove #{file})
                             vec))))

(defn ^:private file-list [files]
  (om/component
   (html
    [:ul.files
     (om/build-all file-row files
                   {:init-state {:hide-fn (partial remove-file! files)}})])))

(declare add-file)

(defn ^:private upload-button [upload owner]
  (om/component
   (html
    [:form.upload {:on-submit #(.preventDefault %)}
     [:input {:type "file"
              :multiple true
              :on-change (fn [e]
                           (let [files (array-seq e.currentTarget.files)]
                             (println "Adding" (count files) "files")
                             (om/transact! upload
                                           #(reduce add-file % files))
                             (set! e.currentTarget.value nil)))}]])))

(defn add-file [upload file]
  (if-not (upload/allowed-file-type? (.-type file))
    (do
      (js/console.warn "Unsupported file type:" (.-type file) file)
      upload)
    (update-in upload [:files]
               #(vec (conj % {:file file})))))

(defn upload-dialog [upload owner _]
  (reify
    om/IRenderState
    (render-state [_ {:keys [mini?]}]
      (let [files (:files upload)
            toggle-mini (fn [e]
                          (om/update-state! owner :mini? not)
                          (.stopPropagation e))]
        (html
         [:div#upload {:class [(when mini? "mini")]
                       :on-click (when mini? toggle-mini)}
          (if-not mini?
            (list
             [:header {:on-click toggle-mini}]
             (om/build file-list files)
             (om/build upload-button upload))
            "Upload Files")])))))
