(ns pepa.model
  (:require [om.core :as om]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.spec :as s]

            [cljs.core.async :as async :refer [<!]])
  (:require-macros
   [cljs.core.async.macros :refer [go-loop]]))

(defrecord Page [id
                 rotation
                 render-status
                 dpi])

(defrecord Document [id
                     title
                     pages
                     created
                     modified
                     document-date
                     notes])

(defrecord State [documents
                  inbox
                  navigation
                  tags
                  upload
                  seqs])

(defonce state (atom (map->State {:documents   {}
                                  :navigation  {:route :dashboard}
                                  :tags        {}
                                  :inbox       {:pages []}
                                  :upload      {}
                                  :ui/sidebars {}
                                  :seqs        {}})))



;;; Storage of Pages/Documents

(defn store-document! [document]
  ;; TODO: Better validation
  (assert (:id document))
  (assert (vector? (:pages document)))
  (swap! state
         assoc-in
         [:documents (:id document)]
         document))

;;; Tag Handling

(defn normalize-tag [tag]
  (assert (string? tag))
  (-> tag
      (str/lower-case)
      (str/trim)))

(defn add-tags [tags new-tags]
  (into tags
        (remove (set tags))
        new-tags))

(defn remove-tags [tags removed-tags]
  (into (empty tags)
        (remove (set removed-tags))
        tags))

(defn all-tags [state]
  (-> state :tags keys set))

(defn sorted-tags [state]
  (->> (:tags state)
       (om/value)
       (remove (comp zero? val))
       (sort-by val >)
       (mapv key)))

(defn tag-count-map
  ([state]
   (om/value (:tags state)))
  ([state only-positive?]
   (if only-positive?
     (into {}
           (filter (comp pos? val))
           (om/value (:tags state)))
     (tag-count-map state))))

;;; Page Handling
;; TODO: We could move this to pepa.inbox

(defn insert-pages
  "Split PAGES at IDX, insert NEW-PAGES in-between. If NEW-PAGES
  contains pages from PAGES they will be moved."
  [pages new-pages idx]
  {:pre [(> idx -1)]}
  (let [idx (min idx (count pages))
        [before after] (split-at idx pages)
        ;; Remove pages in `new-pages' from `before' and `after'. This
        ;; allows us to reorder sequences.
        before (remove (set new-pages) before)
        after (remove (set new-pages) after)]
    (into (empty pages)
          (concat before new-pages after))))

;;; Resizable Sidebars

(defn ui-sidebars []
  (-> state
      (om/root-cursor)
      :ui/sidebars
      (om/ref-cursor)))
