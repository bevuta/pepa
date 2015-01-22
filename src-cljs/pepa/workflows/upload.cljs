(ns pepa.workflows.upload
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [clojure.string :as s]

            [pepa.api.upload :as upload]
            [pepa.data :as data]

            [pepa.navigation :as nav]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn ^:private update-progress-loop [row ch]
  (go-loop []
    (when-let [progress (<! ch)]
      (println "progress-event:" (pr-str progress))
      (cond 
        (number? progress)
        (om/update! row :progress progress))
      (recur))))

(defn ^:private upload-file! [row & [e]]
  (when e
   (.preventDefault e))
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
              progress (async/chan (async/sliding-buffer 1)) 
              id-ch (upload/upload-document! document upload-blob
                                             progress)]
          (update-progress-loop row progress)
          (let [id (<! id-ch)]
           (println "Successfully uploaded document with id" id)
           (om/update! row :document-id id)))
        (finally
          (om/update! row :working? false))))))

(defn ^:provate progress-bar [progress]
  (om/component
   (html
    [:.progress
     [:.bar {:style {:width (str (* progress 100) "%")}}]])))

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
          (if document-id
            [:a.title {:href (when document-id
                               (nav/document-route {:id document-id}))
                       :title name}
             name]
            [:span.title {:title name} name])
          [:span.size (str (byte->kb size) "kB")]
          
          
          (if document-id
            [:.hide {:on-click (fn [e]
                                 (when (fn? hide-fn)
                                   (hide-fn (om/value row)))
                                 (doto e
                                   (.preventDefault)
                                   (.stopPropagation)))}
             "Hide"]
            
            (om/build progress-bar (or (:progress row) 0)))])))))

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
               #(conj (vec %) {:file file}))))

(defn upload-dialog [upload owner _]
  (reify
    om/IDidUpdate
    (did-update [_ prev-props _]
      ;; Start upload for every new file in :files
      (when (< (count (:files prev-props))
               (count (:files upload)))
        (let [new-files (filter (fn [file]
                                  (and (not (:working? file))
                                       (not (:document-id file))))
                                (:files upload))]
          (println "got new files" new-files)
          (doseq [file new-files]
            (upload-file! file)))))
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
             ;; Not sure if {:key :file} works.
             (om/build file-list files {:key :file})
             (om/build upload-button upload))
            "Upload Files")])))))
