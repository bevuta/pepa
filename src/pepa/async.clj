(ns pepa.async
  (:require [clojure.core.async.impl.protocols :as async-impl])
  (:import java.util.LinkedList))

(deftype CollapsingBuffer [^LinkedList buf ^long n collapse-fn]
  async-impl/UnblockingBuffer
  async-impl/Buffer
  (full? [this]
    false)
  (remove! [this]
    (.removeLast buf))
  (add!* [this itm]
    (.addFirst buf itm)
    (when (> (.size buf) n)
      (let [collapsed (collapse-fn (seq buf))]
        (.clear buf)
        (.addFirst buf collapsed)))
    this)
  clojure.lang.Counted
  (count [this]
    (.size buf)))

(defn collapsing-buffer [size collapse-fn]
  {:pre [(integer? size) (fn? collapse-fn)]}
  (CollapsingBuffer. (LinkedList.) size collapse-fn))
