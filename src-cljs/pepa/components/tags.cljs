(ns pepa.components.tags
  (:require [om.core :as om :include-macros true]

            [pepa.model :as model]
            [pepa.navigation :as nav]
            [nom.ui :as ui]

            [cljs.core.async :as async :refer [<!]]
            [cljs.core.match]
            [clojure.string :as s]

            [cognitect.transit :as transit]

            [goog.events.KeyCodes :as keycodes])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cljs.core.match.macros :refer [match]]))

(defn ^:private tag-color [s]
  (let [h (hash s)]
    (str "rgb("
         (s/join ", "
                 [(bit-and (bit-shift-right h 16) 255)
                  (bit-and (bit-shift-right h 8) 255)
                  (bit-and h 255)])
         ")")))

(def ^:private tag-data-type "application/x-pepa-tags")

;;; TODO(mu): EDN should suffice here. I'm writing this in a car
;;; without internet connection and can't remember the EDN namespace.
(let [reader (transit/reader :json)
      writer (transit/writer :json)]
  (defn data-transfer-tags [data-transfer]
    (when-let [data (.getData data-transfer tag-data-type)]
      (when-not (s/blank? data)
        (transit/read reader data))))

  (defn ^:private store-tags! [data-transfer tags]
    (doto data-transfer
      (.setData tag-data-type (transit/write writer tags))
      (.setData "text/plain" (s/join " " (map #(str "tag:" %) tags))))))

(defn accept-tags-drop [e]
  ;; NOTE: Chrome doesn't allow .getData in on-drag-over. I hate it.
  (when (some #{tag-data-type} (array-seq e.dataTransfer.types))
    (.preventDefault e)))

(defn ^:private handle-drag-start [tag e]
  (let [transfer e.dataTransfer]
    (store-tags! transfer #{tag})
    (set! (.-effectAllowed transfer) "copyLink")))

(defn ^:private send-event! [owner event]
  (when-let [events (om/get-state owner :events)]
    (async/put! events event)
    ;; Explicitly return nil so we can use it in event handler fns
    ;; (put! might return false)
    nil))

(def tag-remove-keycode?
  #{keycodes/BACKSPACE
    keycodes/DELETE})

(defn ^:private handle-tag-key-down! [tag owner e]
  (when (tag-remove-keycode? (.-keyCode e))
    (send-event! owner [:remove tag])
    (.preventDefault e)))

;;; `tag' can either be a string or a vector [name document-count]
(ui/defcomponent tag [tag owner _]
  (did-update [_ prev-props prev-state]
    (when (and (not (contains? (:selected prev-state) tag))
               (contains? (om/get-state owner :selected) tag))
      (.focus (om/get-node owner))))
  (render-state [_ {:keys [selected]}]
    (let [[tag document-count] (if (string? tag) [tag nil] tag)]
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
                    (nav/tag-search-route tag))}
        [:span.color {:style {:background-color (tag-color tag)}}]
        [:span.tag-name tag]
        (when (number? document-count)
          [:span.count {:title (str document-count " documents have this tag")}
           document-count])]])))

(defn ^:private document-tags [documents]
  (distinct (mapcat :tags documents)))

(defn ^:private remove-tag!
  "Removes tag. Might alter DOCUMENT or put [:remove TAG] into
  the :tag-changes channel of OWNER. No-op if DOCUMENT already has TAG."
  [documents owner tag]
  (when (some #{tag} (document-tags documents))
    (if-let [chan (om/get-state owner :tag-changes)]
      (async/put! chan [:remove tag])
      (doseq [document documents]
        (om/transact! document :tags #(model/remove-tags % #{tag}))))))

(defn ^:private add-tag!
  "Adds TAG. Might alter DOCUMENT or put [:add TAG] into the :tag-changes
  channel of OWNER. No-op if DOCUMENT already has TAG."
  [documents owner tag]
  (when-not (some #{tag} (document-tags documents))
    (if-let [chan (om/get-state owner :tag-changes)]
      (async/put! chan [:add tag])
      (doseq [document documents]
        (om/transact! document :tags #(model/add-tags % #{tag}))))))

(defn ^:private sseq
  "like seq but for strings"
  [s]
  (when-not (s/blank? s)
    s))

(defn ^:private parse-tag [s]
  (-> s
      (str)
      (s/trim)
      (s/replace "," "")
      (s/lower-case)
      (sseq)))

(defn store-tag!
  "Attempts to parse a tag from the value of the tags input field and
  stores it in the document(s)."
  [documents owner]
  (let [el (om/get-node owner "tag-input")]
    (when-let [tag (parse-tag (.-value el))]
      (add-tag! documents owner tag)
      (set! (.-value el) "")
      true)))

(def tag-delimiter? #{keycodes/TAB
                      keycodes/ENTER
                      keycodes/COMMA})

(defn ^:private cursor-at-start?
  "Returns true if the caret is at position 0 in INPUT."
  [input]
  (or (s/blank? (.-value input))
      (and (zero? (.-selectionStart input))
           (zero? (.-selectionEnd input)))))

(defn handle-tags-key-down! [documents owner e]
  (let [element (om/get-node owner "tag-input")]
    (let [key-code (.-keyCode e)]
      (cond
        ;; Catch tag-delimiting keystrokes and parse the input as tag
        (tag-delimiter? key-code)
        (when (or (store-tag! documents owner)
                  (= keycodes/COMMA key-code))
          (.preventDefault e))

        (and (= keycodes/BACKSPACE key-code)
             (cursor-at-start? element))
        (send-event! owner [:focus (om/value (last (document-tags documents)))])))))

(defn handle-tags-blur! [documents owner e]
  (when (store-tag! documents owner)
    (.preventDefault e)))

(defn ^:private focus-tag-input [owner]
  (some-> owner
          (om/get-node "tag-input")
          (.focus)))

(ui/defcomponent tags-input [documents owner _]
  (init-state [_]
    {:selected #{}
     :events (async/chan)})
  (will-mount [_]
    (go-loop []
      (when-let [event (<! (om/get-state owner :events))]
        (let [[event tag] event
              documents (om/get-props owner)]
          (case event
            ;; Click just toggles selection-status
            :focus (om/set-state! owner :selected #{tag})
            :blur (om/update-state! owner :selected #(disj % tag))
            :remove (do (remove-tag! documents owner tag)
                        (focus-tag-input owner)))
          (recur)))))
  (render-state [_ {:keys [events selected]}]
    [:ul.tags {:class "tag-box"
               :tab-index 0
               :on-click (fn [e]
                           (ui/cancel-event e)
                           (focus-tag-input owner))
               ;; Catch keys coming from tags and redirect them to
               ;; the input field
               :on-key-down (fn [e]
                              (let [text (js/String.fromCharCode (.-keyCode e))]
                                (when (re-find #"\w" text)
                                  (focus-tag-input owner)
                                  (.stopPropagation e))))}
     (om/build-all tag (document-tags documents)
                   {:key-fn identity
                    :init-state {:events events}
                    :state {:selected selected}})
     [:li.input
      [:input {:ref "tag-input"
               :tab-index 0
               :on-key-down (partial handle-tags-key-down! documents owner)
               :on-blur (partial handle-tags-blur! documents owner)}]]]))

;;; TOOD: Support selection of multiple tags for drag&drop
(ui/defcomponent tags-list [tags]
  (render [_]
    [:ul.tags
     (om/build-all tag tags {:key-fn identity})]))
