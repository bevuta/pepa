(ns pepa.async)

(defmacro <? [ch]
  `(let [e# (cljs.core.async/<! ~ch)]
     (when (instance? js/Error e#) (throw e#))
     e#))

(defmacro try-go [& body]
  `(cljs.core.async.macros/go
     (try
       ~@body
       (catch js/Error e#
         e#))))
