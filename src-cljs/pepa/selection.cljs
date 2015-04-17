(ns pepa.selection)

(defrecord Selection [elements selected last-clicked])
(defrecord Click [element modifiers])

(def shift ::shift)
(def control ::control)
(def alt ::alt)

(defn make-click [element modifiers]
  {:pre [element (every? #{shift control alt} modifiers)]}
  (map->Click {:element element
               :modifiers (set modifiers)}))

(defn event->click [element e]
  (make-click element
              (->> [(when e.shiftKey shift)
                    (when e.ctrlKey  control)
                    (when e.altKey   alt)]
                   (remove nil?)
                   (into #{}))))

(defn make-selection [elements]
  (->Selection elements #{} nil))

(defn ^:private toggle-selection [selection element]
  (let [selected (:selected selection)]
    (update selection :selected
            (comp set (if (contains? selected element) disj conj))
            element)))

(defn ^:private range-selection [selection element]
  (let [{:keys [selected elements]} selection
        first-element (some (conj selected element) elements)
        last-element  (some (conj selected element) (reverse elements))
        new-selected (->> elements
                          (drop-while #(not= first-element %))
                          (take-while #(not=  last-element %))
                          (into #{first-element last-element}))]
    (update selection :selected into new-selected)))

(defn click [selection click]
  {:pre [(contains? (set (:elements selection)) (:element click))]}
  (let [{:keys [element modifiers]} click]
    (cond
      (and (empty? modifiers)
           (nil? element))
      (assoc selection
             :selected #{}
             :last-clicked nil)
      
      (empty? modifiers)
      (assoc selection
             :selected (if (= (:selected selection) #{element})
                         #{}
                         #{element})
             :last-clicked element)

      (control modifiers)
      (-> selection
          (toggle-selection element)
          (assoc :last-clicked element))

      (shift modifiers)
      (-> selection
          (range-selection element)
          (assoc :last-clicked element)))))

;;; No modifiers

(let [s (-> (->Selection (range 10) nil nil)
            (click (make-click 3 [])))]
  (assert (= #{3} (:selected s)))
  (assert (= 3 (:last-clicked s))))

(let [s (-> (->Selection (range 10) nil nil)
            (click (make-click 3 []))
            (click (make-click 5 [])))]
  (assert (= #{5} (:selected s)))
  (assert (= 5 (:last-clicked s))))

(let [s (-> (->Selection (range 10) nil nil)
            (click (make-click 3 []))
            (click (make-click 3 [])))]
  (assert (= #{} (:selected s)))
  (assert (= 3 (:last-clicked s))))

;;; Control Selection

(let [s (-> (->Selection (range 10) nil nil)
            (click (make-click 3 [control])))]
  (assert (= #{3} (:selected s)))
  (assert (= 3 (:last-clicked s))))

(let [s (-> (->Selection (range 10) nil nil)
            (click (make-click 3 [control]))
            (click (make-click 3 [control])))]
  (assert (= #{} (:selected s)))
  (assert (= 3 (:last-clicked s))))

(let [s (-> (->Selection (range 10) nil nil)
            (click (make-click 3 [control]))
            (click (make-click 5 [control])))]
  (assert (= #{3 5} (:selected s)))
  (assert (= 5 (:last-clicked s))))

;;; Shift Selection

(let [s (-> (->Selection (range 10) nil nil)
            (click (make-click 3 []))
            (click (make-click 7 [shift])))]
  (assert (= #{3 4 5 6 7} (:selected s)))
  (assert (= 7 (:last-clicked s))))

(let [s (-> (->Selection (range 10) nil nil)
            (click (make-click 7 []))
            (click (make-click 3 [shift])))]
  (assert (= #{3 4 5 6 7} (:selected s)))
  (assert (= 3 (:last-clicked s))))

(let [s (-> (->Selection (range 10) nil nil)
            (click (make-click 7 []))
            (click (make-click 7 [shift])))]
  (assert (= #{7} (:selected s)))
  (assert (= 7 (:last-clicked s))))
