;;; Directory Local Variables
;;; See Info node `(emacs) Directory Variables' for more information.

((clojure-mode
  (eval put-clojure-indent 'match 1)
  (eval put-clojure-indent 'record 1)
  (eval put-clojure-indent 'let->> 1)
  
  (put 'defcomponent 'clojure-backtracking-indent '(4 (2)))
  (put 'ui/defcomponent 'clojure-backtracking-indent '(4 (2)))))

