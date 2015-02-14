(ns pepa.ui
  (:require [om.core :as om]
            [garden.units :as u :refer [px em pt]])
  (:require-macros pepa.ui))

(defn css-fade [animation-name duration]
  (let [pre (str "&." (name animation-name))]
    (list
     [(str pre "-enter")
      {:opacity 0.00, :transition [[:opacity duration :ease-in]]}
      [(str pre "-enter-active") {:opacity 1}]]
     [(str pre "-leave") {:opacity 0.01, :transition [[:opacity duration :ease-in]]}
      [(str pre "-leave-active") {:opacity 0.01}]])))
