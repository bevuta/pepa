(ns pepa.search.parser
  (:refer-clojure :exclude [char atom])
  (:require [the.parsatron
             :refer [always
                     any-char
                     attempt
                     between
                     char
                     choice
                     either
                     eof
                     letter
                     many
                     never
                     string
                     token
                     many1]
             :as parse])
  (:require-macros [the.parsatron :refer [defparser let->> >>]]))

(def identifiers #{"date" "title"})
(def ops #{\=})
(def reserved-words #{"and" "or"})

(def whitespace-char (token #{\space \newline \tab}))
(def optional-whitespace (many whitespace-char))
(def whitespace (many1 whitespace-char))

(def open-paren (char \())
(def close-paren (char \)))


(defparser quoted-string []
  (let->> [s (between (char \")
                      (char \")
                      (many (token (complement #{\"}))))]
    (always (apply str s))) )

(defparser simple-string []
  (let->> [s (-> #{\tab \space \newline \( \) \"}
                 (complement)
                 (token)
                 (many1))]
    (let [s (apply str s)]
      (if-not (contains? reserved-words s)
        (always s)
        (never)))))

(defparser untagged []
  (string "untagged")
  (always (list 'untagged)))

(def tag-prefix
  (either (string "tag")
          (string "is")))

(defparser tag []
  (let->> [_ tag-prefix
           _ (char \:)
           tag (either
                (quoted-string)
                (simple-string))]
    (always (list 'tag (apply str tag)))))

(defparser identifier []
  (let->> [i (->> identifiers
                  (map (comp attempt string))
                  (apply choice))]
    (always (symbol (apply str i)))))

(defparser op []
  (let->> [o (->> ops
                  (map (comp attempt string))
                  (apply choice))]
    (always (symbol (apply str o)))))

(defparser op-expression []
  (let->> [id (identifier)
           _ whitespace
           op (op)
           _ whitespace
           val (either (attempt (simple-string))
                       (attempt (quoted-string)))]
    (always (list op id val))))

(defparser like-op []
  (let->> [id (identifier)
           _ (string ":")
           val (either (attempt (simple-string))
                       (attempt (quoted-string)))]
    (always (list 'like id (str "%" val "%")))))

(defparser binary-predicate []
  (let->> [op (either (string "or")
                      (string "and"))]
    (always (symbol (apply str op)))))

(defparser not-predicate []
  (either (char \!)
          (>> (string "not") whitespace))
  (always 'not))

(declare expr-rec)

(defparser atom []
  (choice (either (attempt (untagged))
                  (attempt (tag)))
          (let->> [s (either (attempt (simple-string))
                             (attempt (quoted-string)))]
            (always (list 'any s)))))

(defparser not-expression []
  (let->> [_ (not-predicate)
           expr (expr-rec)]
    (always (list 'not expr))))

(defparser grouped-expression []
  (between open-paren
           ;; `either' to support dangling parentheses: (bar (bar)
           (either close-paren
                   (eof))
           (expr-rec)))

(defparser expr-norec []
  (choice (attempt (like-op))
          (attempt (op-expression))
          (attempt (grouped-expression))
          (attempt (not-expression))
          (attempt (atom))))

(defparser binary-expr []
  (let->> [t1 (expr-norec)
           _ whitespace
           op (binary-predicate)
           _ whitespace
           t2 (expr-rec)]
    (always (list op t1 t2))))

(defparser expr-rec []
  (choice (attempt (binary-expr))
          (attempt (expr-norec))))

(defparser sequential-expression []
  (let->> [x (many (between optional-whitespace
                            optional-whitespace
                            (expr-rec)))]
    (always (apply list 'or x))))

(def expression sequential-expression)

(defn parse-string [s]
  (try
    (parse/run (expression) s)
    (catch js/Error e
      (println "error:" e)
      nil)))
