;;; Directory Local Variables
;;; See Info node `(emacs) Directory Variables' for more information.

((clojure-mode
  (eval put-clojure-indent 'match 1)
  (eval put-clojure-indent 'record 1)
  (eval put-clojure-indent 'let->> 1))
 (clojurescript-mode
  (eval put-clojure-indent 'match 1)
  (eval put-clojure-indent 'record 1)
  (eval put-clojure-indent 'let->> 1)
  
  (eval put-clojure-indent 'defcomponent '(nil (:defn)))
  (eval put-clojure-indent 'ui/defcomponent '(nil (:defn)))

  (eval put-clojure-indent 'defcomponentmethod '(nil (:defn)))
  (eval put-clojure-indent 'ui/defcomponentmethod '(nil (:defn)))))

