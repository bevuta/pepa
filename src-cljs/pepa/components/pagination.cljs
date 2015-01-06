(ns pepa.components.pagination
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]))

(def +items-per-page+ 10)

(defn pages
  ([items per-page]
   (Math/ceil (/ (count items)
                 per-page)))
  ([items]
   (pages items +items-per-page+)))

(defn page-items
  "Returns a subseq of ITEMS for PAGE. First PAGE is 1."
  ([items page per-page]
   (->> items
        (drop (* per-page (dec page)))
        (take per-page)))
  ([items page]
   (page-items items page +items-per-page+)))

(defn page-range
  "Returns a vector [start end] describing the range of PAGE in ITEMS.
  For example, first page would have the range [1 10], second [11 20]
  with 10 items per page."
  ([items page per-page]
   (when-let [pitems (seq (page-items items page per-page))]
     (let [start (inc (* per-page (dec page)))
           end (+ (dec start) (count pitems))]
       [start end])))
  ([items page]
   (page-range items page +items-per-page+)))
