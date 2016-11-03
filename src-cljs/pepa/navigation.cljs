(ns pepa.navigation
  (:require [clojure.string :as s]
            [bidi.bidi :as bidi]
            [goog.string :as gstring]
            cljs.core.match)
  (:require-macros [cljs.core.match.macros :refer [match]]))

(def routes ["/" [[[]                               :dashboard]
                  [["inbox/" :columns]              :inbox]
                  [["document/" :id "/page/" :page] :document-page]
                  [["document/" :id]                :document]
                  [["search/tag/" :tag]             :tag-search]
                  [["search/" [#".+" :query]]       :search]]])

(defn dashboard-route []
  (str "/#" (bidi/path-for routes :dashboard)))

(defn inbox-route
  ([]
   (inbox-route "i"))
  ([columns]
   (str "/#" (bidi/path-for routes :inbox :columns columns))))

(defn edit-document-route [document-id]
  (->>
   ;; TODO: This strings needs to be generated from pepa.inbox2
   (str "i,d:" document-id ",n")
   (inbox-route)))

(defn document-route
  ([document-id page]
   (assert document-id page)
   (str "/#" (bidi/path-for routes :document-page :id document-id :page page)))
  ([document-id]
   (assert document-id)
   (str "/#" (bidi/path-for routes :document :id document-id))))

(defn tag-search-route [tag]
  (assert (string? tag))
  (str "/#" (bidi/path-for routes :tag-search :tag tag)))

(defn search-route [query]
  (assert (string? query))
  (str "/#" (bidi/path-for routes :search :query (js/encodeURIComponent query))))

(defn parse-route [route]
  (assert (string? route))
  (when-let [matched (->> (s/replace-first route "/#" "")
                          (bidi/match-route routes))]
    (cond-> matched
      (= :search (:handler matched))
      (update-in [:route-params :query] js/encodeURIComponent))))

(defn navigate! [route & [ignore-history no-dispatch]]
  (if ignore-history
    (do (js/window.history.replaceState nil "" route)
        (when-not no-dispatch
          ;; TODO/refactor
          (throw (ex-info "Unimplemented" {}))
          #_(secretary/dispatch! (.substring route 1))))
    (set! (.-location js/window) route)))

(defn nav->route [{:keys [handler query-params]}]
  (match [handler]
    [:dashboard]
    (dashboard-route)

    ;; TODO/rewrite
    ;; [[:document id]]
    ;; (document-route id query-params)

    ;; [[:search [:tag tag]]]
    ;; (tag-search-route tag query-params)

    ;; [[:search [:query query]]]
    ;; (search-route query query-params)
    ))

;;; TODO/refactor: Move to sidebar
(def navigation-elements
    [["Inbox"     :inbox     #{:inbox}             (inbox-route)]
     ["Documents" :dashboard #{:dashboard :search} (dashboard-route)]
     ["Tags"      :tags      #{}           nil]])
