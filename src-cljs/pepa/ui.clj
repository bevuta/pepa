(ns pepa.ui)

(defmacro css-transition-group [options & body]
  `(let [group# js/React.addons.CSSTransitionGroup
         options# (cljs.core/clj->js ~options)]
     (assert group#)
     (js/React.addons.CSSTransitionGroup
      options#
      (sablono.core/html ~@body))))

(defmacro with-working [target & body]
  `(let [target# ~target
         f# (if (om.core/transactable? target#)
              om.core/update!
              om.core/set-state!)]
     (try
       (f# target# :working? true)
       (do ~@body)
       (finally
         (f# target# :working? false)))))
