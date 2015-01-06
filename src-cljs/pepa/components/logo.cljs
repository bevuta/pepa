(ns pepa.components.logo
  (:require [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]

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
    (set! (.-onmousemove js/window) 
          (fn [e]
            (let [[dx dy] (vec- [e.clientX e.clientY] center)
                  transform (str "translate("
                                 (min (* eyeball-movement
                                         (/ dx js/window.screen.width))
                                      eyeball-max)
                                 ", "
                                 (min (* eyeball-movement
                                         (/ dy js/window.screen.height))
                                      eyeball-max)
                                 ")")]
              (.setAttribute eyeball "transform" transform))))))

(defn xeyes [_ owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (let [obj (om/get-node owner "logo")]
        (.addEventListener obj "load" #(attach-eyeball-mover! obj))))
    om/IRender
    (render [_]
      (html
       [:a {:href (nav/dashboard-route)}
        [:header
         [:object.logo {:ref "logo"
                        :data "img/logo-2.svg"
                        :type "image/svg+xml"}]
         [:span.brand "Pepa"]
         [:span.name "DMS"]]]))))
