(ns pepa.components.utils
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]

            [clojure.string :as s]))

(defn focus-if
  "Focus input with REF if KEY is non-nil in CURRENT-STATE and nil in
  PREV-STATE.. If MOVE-CARET? is non-nil, also move the caret to the
  last position."
  [owner prev-state key ref & [move-caret?]]
  (when (and (not (get prev-state key))
             (om/get-state owner key))
    (when-let [input (om/get-node owner ref)]
      (.focus input)
      ;; Move caret to the end (if supported)
      (when (and move-caret? (.-setSelectionRange input))
        (let [l (count (.-value input))]
          (.setSelectionRange input l l))))))
