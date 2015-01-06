(ns pepa.data
  (:require [om.core :as om]
            [clojure.set :as set]
            [clojure.string :as s]))

(defrecord Page [id])
(defrecord Document [id title pages tags created modified notes])

(defrecord State [documents navigation upload])

(defonce state (atom (map->State {:documents {}
                                  :navigation {:route :dashboard}
                                  :upload {}})))

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
  (set/union (set tags) (set new-tags)))

(defn remove-tags [tags removed-tags]
  (set/difference (set tags) (set removed-tags)))

;;; Page Movement

(declare move-pages)

(defn add-pages
  "Adds pages to document. Two argument version inserts them at the
  back. Four-arg version inserts pages before or after page, depending
  on position which can be :before or :after."
  ([document pages position page]
     (update-in document [:pages]
                (fn [dpages]
                  (move-pages dpages pages position page))))
  ([document pages]
     (add-pages document pages :before nil)))

(defn remove-pages
  "Removes pages from document."
  [document pages]
  (update-in document [:pages]
             #(into (empty %) (remove (set pages) %))))

(defn move-pages
  "Removes pages-to-move (a seq) from pages (a seq) and inserts it
  before (if position is :before) or after (if position is :after)
  target (a page). If target is nil, insert at the top."
  [pages pages-to-move position target]
  (assert (#{:before :after} position))
  (assert (not (contains? (set pages-to-move) target)))
  (let [empty-pages (empty pages)
        pages (remove (set pages-to-move) pages)
        [pre x post] (cond

                      (nil? target)
                      ;; Handle "insert at bottom"
                      [pages nil nil]

                      (= target (first pages))
                      [nil [(first pages)] (rest pages)]

                      (= target (last pages))
                      [(butlast pages) [(last pages)] nil]
                      
                      true
                      (partition-by #{target} pages))]
    (into empty-pages
          (concat pre
                  (when (= :before position) pages-to-move)
                  x
                  (when (= :after position) pages-to-move)
                  post))))

;;; TODO: Simulation Testing

(assert (= (move-pages [:foo :bar :baz :bla]
                 [:bar]
                 :after :bla)
           [:foo :baz :bla :bar]))

(assert (= (move-pages [:foo :bar :baz :bla]
                 [:bar]
                 :before :bla)
           [:foo :baz :bar :bla]))

(assert (= (move-pages [:foo :bar :baz :bla]
                 [:foo :bar]
                 :before :bla)
           [:baz :foo :bar :bla]))

(assert (= (move-pages [:foo :bar :baz :bla]
                 [:foo :bar]
                 :before nil)
           [:baz :bla :foo :bar]))

(assert (= (move-pages [:foo :bar :baz :bla]
                 [:foo :bar]
                 :after nil)
           [:baz :bla :foo :bar]))

(assert (= (move-pages [:foo :bar :baz :bla]
                 [:foo :bar :baz :bla]
                 :before nil)
           [:foo :bar :baz :bla]))

(assert (= (move-pages [:foo :bar :baz :bla]
                 [:foo :bar :baz]
                 :after :bla)
           [:bla :foo :bar :baz]))

(assert (= (move-pages [:foo :bar :baz :bla]
                 [:foo :bar :baz]
                 :before :bla)
           [:foo :bar :baz :bla]))

(comment
  ;; Extend ICursor to nil
  (deftype NilCursor [state path]
    om/ICursor
    (-path [_] path)
    (-state [_] state))

  (extend-type nil
    om/IToCursor
    (-to-cursor [value state]
      (om/-to-cursor value state nil))
    (-to-cursor [_ state path]
      (NilCursor. state path))

    ICloneable
    (-clone [_] nil)))
