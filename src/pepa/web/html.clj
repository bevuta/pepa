(ns pepa.web.html
  (:require [hiccup.page :refer [html5 include-js]]))

(defn page-img [id & [{:keys [width href src]}]]
  (let [width (or width 300)
        src (or src (str "/pages/" id "/image"))
        href (or href src)]
    [:a {:href href} [:img {:src src :width width}]]))

(defn inbox [pages]
  (let [options (map #(vector :option {:value %} %)
                     (cons "" (range 1 (inc (count pages)))))]
    (html5
     [:div#inbox
      [:h1 "Inbox"]
      [:form {:action "/documents" :method "POST"}
       [:label {:for "document_title"} "Title"]
       [:input {:type "text" :name "title" :id "document_title"}]
       (for [file-pages (partition-by :file pages)]
         [:ol
          (for [{:keys [id number]} file-pages]
            [:li
             (page-img id)
             [:select {:name (str "page-" id)} options]])])
       [:button {:type "submit"} "Create document"]]])))

(defn documents [docs]
  (html5
   [:h1 "Documents Overview"]
   [:ul (for [{:keys [id title page]} docs]
          (let [href (str "/documents/" id)]
            [:li [:h2 [:a {:href href} title]]
             (page-img page {:href href})]))]))

(defn document [document]
  (html5
   [:div.document
    [:h2 (or (:title document)
             "Unnamed Document")]
    [:ol
     (for [{page-id :id} (:pages document)]
       [:li (page-img page-id)])]]))

(defn tags [tags]
  (html5 [:h1 "All tags"
          [:h3
           (for [{tag :name} tags]
             [:li
              [:ol [:a {:href (str "/tags/" tag)} tag]]])]]))

(defn objects-for-tag [tag files pages documents]
  (html5 [:h1 (str "Objects for tag " tag)]
         (map (fn [{:keys [title rows row-fn]}]
                [:li
                 [:h2 title]
                 [:ol (map (fn [r] [:li (row-fn r)]) rows)]])
              [{:title "Files"
                :rows files
                :row-fn (fn [{name :name origin :origin id :id}]
                          (list
                           (str "Name: " name "; Origin: " origin "; ID: " id "; ")
                           [:a {:href (str "/files/" origin)} (str "Link")]))}
               {:title "Pages"
                :rows pages
                :row-fn (fn [{id :id number :page}]
                          (list
                           (str "Document-ID: " id "; Number: " number "; ")
                           [:a {:href (str "/pages/" id "/image")} (str "Link")]))}
               {:title "Documents"
                :rows documents
                :row-fn (fn [{title :title id :id}]
                          (list
                           (str "Title: " title "; ID: " id "; ")
                           [:a {:href (str "/documents/" id)} "Link"]))}])))

(defn root []
  (html5
   [:html
    [:head
     [:style#style {:style "text/css"}]]
    [:body
     [:noscript
      [:ul
       [:li
        [:a {:href "/inbox"}
         "Inbox"]]]]
     (include-js "pepa.js")]]))
