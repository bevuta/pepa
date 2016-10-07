(ns pepa.log
  (:require [com.stuartsierra.component :as component]
            clojure.tools.logging
            [clojure.string :as str]))

(defn log [ns level & msgs]
  (let [[ex msgs] (if (instance? Throwable (first msgs))
                    [(first msgs) (rest msgs)]
                    [nil msgs])
        msg (->> msgs
                 (map (fn [msg]
                        (if (string? msg)
                          msg
                          (pr-str msg))))
                 (str/join " "))]
    (clojure.tools.logging/log ns level ex msg)))

(defmacro ^:private defloglevel [level]
  ;; Yay for nested macros
  `(defmacro ~level
     {:arglists '([~'throwable ~'& ~'msgs]
                  [~'& ~'msgs])}
     [& msgs#]
     `(log ~*ns* ~~(keyword level) ~@msgs#)))

(defloglevel trace)
(defloglevel debug)
(defloglevel info)
(defloglevel warn)
(defloglevel error)
(defloglevel fatal)

