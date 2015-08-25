(ns pepa.data
  (:require [om.core :as om]
            [clojure.set :as set]
            [clojure.string :as s]

            [cljs.core.async :as async :refer [<!]])
  (:require-macros
   [cljs.core.async.macros :refer [go-loop]]))

(defrecord Page [id rotation render-status dpi])
(defrecord Document [id title pages created modified document-date notes])

(defrecord State [documents inbox navigation tags upload seqs])

(defonce state (atom (map->State {:documents {}
                                  :navigation {:route :dashboard}
                                  :tags {}
                                  :inbox {:pages []}
                                  :upload {}
                                  :ui/sidebars {}
                                  :seqs {}})))

;;; Document Staleness

(def +staleness-threshold+ (* 60 30))

(defn last-update [document]
  (-> document meta :last-update))

(defn stale-document? [document]
  (when-let [update (last-update document)]
    (> (/ (- (.getTime (js/Date.)) (.getTime update))
          1000)
       +staleness-threshold+)))

(defn stale-documents [documents]
  (into (empty documents)
        (filter stale-document? documents)))

;;; Storage of Pages/Documents

(defn store-document! [document]
  ;; TODO: Better validation
  (assert (:id document))
  (assert (vector? (:pages document)))
  (om/update! (om/root-cursor state)
              [:documents (:id document)]
              document))

(defn store-page! [page]
  ;; TODO: Better validation
  (assert (:id page))
  (assert (:image page))
  (om/update! (om/root-cursor state)
              [:pages (:id page)]
              page))

;;; Tag Handling

(defn normalize-tag [tag]
  (assert (string? tag))
  (-> tag
      (s/lower-case)
      (s/trim)))

(defn add-tags [tags new-tags]
  (->> new-tags
       (remove (set tags))
       (into tags)))

(defn remove-tags [tags removed-tags]
  (->> tags
       (remove (set removed-tags))
       (into (empty tags))))

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
   (-> (:tags state)
       (om/value)))
  ([state only-positive?]
   (if only-positive?
     (->> (:tags state)
          (om/value)
          (filterv (comp pos? val))
          (into {}))
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
    (into (empty pages) (concat before new-pages after))))

;;; Resizable Sidebars

(defn ui-sidebars []
  (-> state
      (om/root-cursor)
      :ui/sidebars
      (om/ref-cursor)))

