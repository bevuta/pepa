(ns pepa.ui
  (:require clojure.string))

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

(defn ^:private method->interface [method]
  (->> (clojure.string/split (str (first method)) #"-")
       (map clojure.string/capitalize)
       (apply str "I")
       (symbol "om.core")))

(defn ^:private wrap-html [method]
  (let [[fname args & body] method]
    (if (contains? #{'render-state 'render} fname)
      `(~fname ~args
               (do
                 ~@(butlast body))
               (sablono.core/html
                ~(last body)))
      method)))

(defmacro defcomponent [name [& args] & methods]
  (let [impls (mapcat (comp (juxt method->interface identity)
                            wrap-html)
                      (remove symbol? methods))
        syms (filter symbol? methods)]
    `(defn ~name [~@args]
       (reify
         om.core/IDisplayName
         (~'display-name [_#]
           ~(clojure.string/capitalize name))
         ~@syms
         ~@impls))))

(comment
  (defcomponent foo [state owner opts]
    (did-update [_ prev-props prev-state]
      (when (and (not (contains? (:selected prev-state) tag))
                 (contains? (om/get-state owner :selected) tag))
        (.focus (om/get-node owner))))
    (render-state [_ {:keys [selected]}]
      [:li.tag {:tab-index 0
                :class [(when (contains? selected tag) "selected")]
                :on-click (fn [e]
                            (.stopPropagation e))
                :on-focus #(send-event! owner [:focus tag])
                :on-blur  #(send-event! owner [:blur tag])
                :on-key-down (partial handle-tag-key-down! tag owner)
                :draggable true
                :on-drag-start (partial handle-drag-start tag)}
       [:a {:href (when-not (om/get-state owner :events)
                    (nav/tag-search tag))}
        [:span.color {:style {:background-color (tag-color tag)}}]
        [:span.tag-name tag]]])))
