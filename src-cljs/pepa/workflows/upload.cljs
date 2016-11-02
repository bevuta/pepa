(ns pepa.workflows.upload
  (:require [om.core :as om :include-macros true]
            [clojure.string :as s]

            [nom.ui :as ui]
            [pepa.api.upload :as upload]
            [pepa.model :as model]
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
              document (model/map->Document {:title name})
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

(ui/defcomponent ^:private progress-bar [progress]
  (render [_]
    [:.progress
     [:.bar {:style {:width (str (* progress 100) "%")}}]]))

(defn ^:private byte->kb [byte]
  (Math/floor (/ byte 1024)))

(ui/defcomponent ^:private file-row [row owner _]
  (render-state [_ {:keys [hide-fn]}]
    (let [document-id (:document-id row)
          file (:file row)
          name (.-name file)
          size (.-size file)
          valid? (:valid? row)]
      [:li {:class [(when-not valid? "invalid")]
            :title (when-not valid?
                     "This file type isn't supported.")}
       (if document-id
         [:a.title {:href (when document-id
                            (nav/document-route document-id))
                    :title name
                    :key "title"}
          name]
         [:span.title (when valid? {:title name, :key "title"})
          name])
       (when valid?
         [:span.size {:key "size"}
          (str (byte->kb size) "kB")])


       (if (or (not valid?) document-id)
         [:.hide {:on-click (fn [e]
                              (when (fn? hide-fn)
                                (hide-fn (om/value row)))
                              (doto e
                                (.preventDefault)
                                (.stopPropagation)))
                  :key "hide"}
          "Hide"]

         (om/build progress-bar (or (:progress row) 0)
                   {:react-key "progress-bar"}))])))

(defn ^:private remove-file! [files file]
  (om/transact! files (fn [files]
                        (->> files
                             (remove #{file})
                             vec))))

(ui/defcomponent ^:private file-list [files]
  (render [_]
    [:ul.files
     (om/build-all file-row files
                   {:init-state {:hide-fn (partial remove-file! files)}})]))

(defn add-file [upload file]
  (update-in upload [:files]
             #(conj (vec %) {:file file
                             :valid? (upload/allowed-file-type? (.-type file))})))

(ui/defcomponent ^:private upload-button [upload owner]
  (render [_]
    [:form.upload {:on-submit #(.preventDefault %)}
     [:input {:type "file"
              :multiple true
              :on-change (fn [e]
                           (let [files (array-seq e.currentTarget.files)]
                             (println "Adding" (count files) "files")
                             (om/transact! upload
                                           #(reduce add-file % files))
                             (set! e.currentTarget.value nil)))}]]))

(ui/defcomponent upload-dialog [upload owner _]
  (did-update [_ prev-props _]
    ;; Start upload for every new file in :files
    (when (< (count (:files prev-props))
             (count (:files upload)))
      (let [new-files (filter (fn [file]
                                (and (not (:working? file))
                                     (not (:document-id file))
                                     (:valid? file)))
                              (:files upload))]
        (println "got new files" new-files)
        (doseq [file new-files]
          (upload-file! file)))))
  (render-state [_ {:keys [mini?]}]
    (let [files (:files upload)
          toggle-mini (fn [e]
                        (om/update-state! owner :mini? not)
                        (.stopPropagation e))]
      [:div#upload {:class [(when mini? "mini")]
                    :on-click (when mini? toggle-mini)}
       (if-not mini?
         (list
          [:header {:on-click toggle-mini
                    :key "header"}]
          ;; Not sure if {:key :file} works.
          (om/build file-list files {:react-key "file"})
          (om/build upload-button upload {:react-key "upload-button"}))
         "Upload Files")])))
