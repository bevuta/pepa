(ns pepa.components.logo
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [clojure.browser.event :as event]
            [goog.style.transform :as transform]
            [goog.dom :as dom]

            [pepa.ui :as ui]
            [pepa.navigation :as nav]))

(defn ^:private normalize [[^number x ^number y]]
  (let [l (Math/sqrt
           (+ (* x x)
              (* y y)))]
    [(/ x l)
     (/ y l)]))

(defn ^number ^:private vec- [[^number x1 ^number y1]
                              [^number x2 ^number y2]]
  [(- x1 x2)
   (- y1 y2)])

(def ^:private eyeball-movement 8)
(def ^:private eyeball-max 5)

(defn ^:private attach-eyeball-mover! [obj]
  (let [xml (.-contentDocument obj)
        eyeball (.getElementById xml "eyeball")
        rect (.getBoundingClientRect obj)
        center [(/ (- (.-right rect) (.-left rect)) 2)
                (/ (- (.-bottom rect) (.-top rect)) 2)]]
    (event/listen js/document.documentElement "mousemove"
                  (fn [e]
                    (let [[dx dy] (vec- [e.clientX e.clientY] center)
                          [dw dh] (let [v (dom/getViewportSize)] [v.width v.height])
                          tx (min (* eyeball-movement (/ dx dw))
                                  eyeball-max)
                          ty (min (* eyeball-movement (/ dy dh))
                                  eyeball-max)]
                      (transform/setTranslation eyeball tx ty))))))

(ui/defcomponent xeyes [_ owner]
  (did-mount [_]
    (let [obj (om/get-node owner "logo")]
      (event/listen obj "load" #(attach-eyeball-mover! obj))))
  (render [_]
    [:a {:href (nav/dashboard-route)}
     [:header
      [:object.logo {:ref "logo"
                     :data "img/logo-2.svg"
                     :type "image/svg+xml"}]
      [:span.brand "Pepa"]
      [:span.name "DMS"]]]))
