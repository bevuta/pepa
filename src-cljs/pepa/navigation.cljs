(ns pepa.navigation
  (:require [clojure.string :as s]
            [clojure.set :refer [rename-keys]]
            
            [bidi.bidi :as bidi]
            [goog.string :as gstring]

            [pepa.model.route :as route]
            [pepa.utils :refer [string->int]]
            
            cljs.core.match)
  (:require-macros [cljs.core.match.macros :refer [match]]))

(def routes ["/" [[[]                               :dashboard]
                  [["inbox/" [#".+" :columns]]      :inbox]
                  [["document/" :id "/page/" :page] :document-page]
                  [["document/" :id]                :document]
                  [["search/tag/"  [#".+" :tag]]    :tag-search]
                  [["search/term/" [#".+" :query]]  :search]]])

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
  {:pre [(or (nil? route) (string? route))]
   :post [(some? %)]}
  (let [route (some-> route
                      (s/replace-first "/#" ""))
        ;; Match route, default to dashboard
        ;; TODO: We might want to show a 404 page
        matched (or (rename-keys (bidi/match-route routes route)
                                 {:handler      ::route/handler
                                  :route-params ::route/route-params})
                    {::route/handler :dashboard
                     ::route/route-params {}})
        {:route/keys [handler]} matched]
    (cond-> matched
      (= :search handler)
      (update-in [::route/route-params :query] js/encodeURIComponent)

      (contains? #{:document :document-page} handler)
      (update-in [::route/route-params :id] string->int)

      (= :document-page handler)
      (update-in [::route/route-params :page] string->int))))

(defn navigate! [route & [ignore-history no-dispatch]]
  (if ignore-history
    (do (js/window.history.replaceState nil "" route)
        (when-not no-dispatch
          ;; TODO/refactor
          (throw (ex-info "Unimplemented" {}))
          #_(secretary/dispatch! (.substring route 1))))
    (set! (.-location js/window) route)))

(defn nav->route [{:keys [handler route-params]}]
  (match [handler]
    [:dashboard]
    (dashboard-route)

    ;; TODO/rewrite
    ;; [[:document id]]
    ;; (document-route id route-params)

    ;; [[:search [:tag tag]]]
    ;; (tag-search-route tag route-params)

    ;; [[:search [:query query]]]
    ;; (search-route query route-params)
    ))

;;; TODO/refactor: Move to sidebar
(def navigation-elements
    [["Inbox"     :inbox     #{:inbox}             (inbox-route)]
     ["Documents" :dashboard #{:dashboard :search} (dashboard-route)]
     ["Tags"      :tags      #{}           nil]])
