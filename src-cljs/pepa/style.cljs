(ns pepa.style
  (:require [garden.core :refer [css]]
            [garden.units :as u :refer [px em pt]]
            [garden.stylesheet :refer [at-keyframes cssfn]]

            [pepa.components.sidebar :refer [navigation-elements]]

            [clojure.string :as s]
            [goog.string :as gstring]))

;;; Colors

(def grey-1 "#303030")
(def grey-2 "#575757")
(def grey-3 "#6f6f6f")

(def dark-background "#f7f7f7")
(def darker-background "#dddddd")
(def light-background "#ffffff")
(def light-blue-background "#d6e3eb")
(def blue-background "#2780ba")

(def sidebar-color dark-background)
(def header-color dark-background)

(def text-color-disabled "#999999")
(def blue-text "#37698c")
(def default-text grey-2)

(def tags-text default-text)
(def tags-background "#e8eaea")
(def tags-selected-background light-blue-background)

(def border-light "#f0f0f0")
(def border-dark "#cbcbcb")
(def border-buttons "#a2a2a2")

(def dashboard-page-count-color "#aaaaaa")

;;; Font

(def default-font  ["Helvetica Neue"
                    "Helvetic"
                    "Arial"
                    "Comic Sans MS"])

;;; Sizes

(def default-sidebar-width 250)
(def max-sidebar-width 600)
(def min-sidebar-width 200)

;;; Helper functions

(def a4-ratio (/ 1 (Math/sqrt 2)))
(def x43-ratio (/ 3 4))

;;; Hack to generate calc() expressions

(defn ^:private fn->symbol [fn]
  (condp = fn
    + '+
    - '-
    * '*
    / '/))

(defn ^:private calc-expression [& ops]
  (let [ops (map #(cond
                    (number? %) (px %)
                    (fn? %) (fn->symbol %)
                    :else %)
                 ops)]
    ((cssfn :calc) ops)))

(defn ^:private calc-str [& ops]
  (garden.compiler/render-css (apply calc-expression ops)))

(defn calc-property
  "Generates css rule map(s) for `property' with calc(ops...)."
  [property [& ops]]
  (let [expr (apply calc-expression ops)
        exprs (map #(str "-" (name %) "-" (apply calc-str ops)) [:moz :webkit])]
    (cons {property expr}
          (for [expr exprs]
            {property expr}))))


(defn image-url [image]
  (str "url(" (str "/img/" image) ")"))

;;; Header

(def header-padding 10)
(def header-height (- 72 header-padding))

(def generic-header-css
  ;; TODO(mu): Position header buttons here
  (let [header-height (- header-height 2)] ;handle border
    [:header {:height (px header-height)
              :line-height (px header-height)
              :background-color header-color
              :padding {:left (px header-padding)
                        :right (px header-padding)}
              :border-bottom (str "2px solid white")}]))

(def draggable-height 10)
(def draggable-width (* 1.5 draggable-height))
(defn draggable-css [position]
  [:.draggable (assoc {:position :absolute
                       :top (px (- (/ header-height 2)
                                   (/ draggable-height 2)))
                       :width (px draggable-width)
                       :height (px draggable-height)
                       :background-color :red}
                      (or position :left)
                      (px (- (/ draggable-width 2))))])

(def sidebar-header-css
  (list
   generic-header-css
   [:header {:text-decoration :none
             :font-size (em 1.2)}
    ;; Logo
    (let [logo-width 38]
      [:.logo {:float :left
               :margin-top (px 10)
               :width (px logo-width)
               :height (px logo-width)}])
    [:span {:padding-left (px 5)}
     [:&.brand {:font-weight 400}]]]))

(defn sidebar-search-css [height]
  [:.search {:height (px height)
             :position :relative
             :border-bottom (str "1px solid " border-dark)}
   [:input (list
            {:width "90%"
             :position :absolute
             :top "50%"
             :left "50%"
             :border (str "1px solid " border-dark)}
            ^:prefix {:transform "translate(-50%,-50%)"})]]  )

(def sidebar-css
  (let [search-height 50]
    [:#sidebar {:height "100%"
                :float :left
                :background-color sidebar-color
                :border-right (str "1px solid " border-dark)
                :position :relative}
     (draggable-css :right)
     sidebar-header-css
     (sidebar-search-css search-height)

     ;; Sections
     [:nav.workflows (list
                      {:overflow-y :auto}
                      (calc-property :height ["100%" - (+ search-height header-height)]))
      [:ul {:padding-left 0, :margin 0}
       (let [item-height 50
             padding (/ item-height 8)
             line-height (/ item-height 1.5)
             icon-size 20
             padding-left 20]
         [:li (list {:list-style-type :none
                     :height (px line-height)
                     :line-height (px line-height)
                     :padding {:top (px padding)
                               :bottom (px padding)}
                     :background {:repeat :no-repeat
                                  :size (px icon-size)
                                  :position [[(px padding-left) :center]]}}
                    ^:prefix {:user-select :none})
          (for [row (map second navigation-elements)]
            (let [row (name row)
                  selector (keyword (str "&." row))]
              [selector
               [:a
                [:div
                 [:&:before {:background-image (image-url (str "menu-icons/" row ".svg"))}]]]
               [:&.active
                [:a
                 [:div
                  [:&:before :&:after {:background-color blue-background}]
                  [:&:before {:background-image (image-url (str "menu-icons/" row "-active.svg"))}]]]]]))
          [:&.inbox {:position :relative}
           [:.drop-target {:background-color darker-background}]
           [:.count {:position :absolute
                     :right (px 26)
                     :font-size (pt 11)
                     :color blue-text}]]
          [:&.tags {:cursor :pointer}
           ;; Use different icon when open?
           [:div.open
            [:&:before {:background-image (image-url "menu-icons/tags-open.svg")}]]
           [:ul {:list-style-type :none
                 :padding-left (px (+ (* 2 padding-left)
                                      icon-size
                                      
                                      ))
                 :overflow-y :auto}
            (let [color-size 12]
              [:li.tag {:display :list-item
                        :position :relative
                        :background :initial
                        :max-width "100%"
                        ;; Not sure if this is a good idea
                        :font-size (px color-size)
                        :line-height (px color-size)
                        :height (px color-size)}
               [:.tag-name (list {:position :absolute
                                  :top "50%"
                                  :overflow :hidden
                                  :text-overflow :ellipsis}
                                 (calc-property :width ["100%" - (* 2 color-size)])
                                 ^:prefix {:transform "translateY(-50%)"})]
               [:.color {:width (px color-size)
                         :height (px color-size)
                         :float :right}]])]]
          
          [:a
           [:div {:height (px line-height)}
            [:&:before :&:after {:content (pr-str " ")
                                 :display :block
                                 :height (px line-height)}]
            [:&:before {:width (px 50)
                        :float :left
                        :margin-right (px 10)
                        :background {:repeat :no-repeat
                                     :position [[(px padding-left) :center]]
                                     :size (px 20)}}]
            [:&:after {:width (px 10)
                       :float :right}]]]])]]]))

(def page-css
  [:.thumbnail {:position :relative}
   (let [rotate-height 25]
     [:.rotate {:position :absolute
                :top 0, :right 0
                :height (px rotate-height)
                :z-index 500
                :background-color "rgba(255, 255, 255, 0.8)"
                :margin (px 5)}
      [:.left :.flip :.right (list
                              {:float :right
                               :width (px rotate-height)
                               :height (px rotate-height)
                               :line-height (px rotate-height)
                               :text-align :center
                               :background-size "100%"
                               :cursor :pointer
                               :opacity 0.3}
                              ^:prefix {:user-select :none})
       ;; Give buttons full opacity when hovered
       [:&:hover {:opacity 1}]
       [:&.right {:background-image (image-url "material/page-rotate-right.svg")}]
       [:&.flip.vertical {:background-image (image-url "material/page-flip-vertical.svg")}]
       [:&.left {:background-image (image-url "material/page-rotate-left.svg")}]]])])

;; [:&.inbox {:background
;; [:&.active {:background-color blue-background}
;;         [:&.inbox {:background {:image (image-url "menu-icons/inbox-active.svg")}}]]
;;; Workflows

(def document-width 250)

;;; Inbox

(def inbox-css
  [:&.inbox {:overflow :auto}
   [:table {:table-layout :fixed
            :border-collapse :collapse}]
   ;; Clearing all table properties. Sucks so hard.
   [:table :tbody :thead :colgroup :tr :td
    {:height "100%"
     :padding 0, :margin 0, :border :none}
    [:tr
     [:td {:position :relative
           :vertical-align :top
           :border-right (str "1px solid " border-light)}]]]
   (let [collapse-height 15
         footer-height 40]
     [:.document (list
                  {:margin-top (px (+ header-height
                                      collapse-height))
                   :width (px document-width)
                   :overflow-y :auto}
                  (calc-property :height ["100%"
                                          - header-height
                                          - collapse-height]))
      ;; Special handling for the inbox document (no .collapse thingy)

      [:&.inbox (list {:margin-top (px header-height)}
                      (calc-property :height ["100%"
                                              - header-height]))]
      (let [header-width (- document-width
                            (* 2 header-padding))]
        ;; TODO(mu): Redo this section
        [:header {:position :absolute
                  :width (px header-width)
                  :height (px header-height)
                  :top 0 :left 0
                  :cursor :pointer}
         [:.title {:max-width "70%"
                   :height "100%"
                   :display :inline-block
                   :white-space :nowrap}
          [:&div {:overflow :hidden
                  :white-space :nowrap
                  :text-overflow :ellipsis
                  :height "100%"}]
          [:&input {:width (px header-width)}]]
         [:button (list
                   {:position :absolute
                    :right (px header-padding)
                    :margin-top (px (/ header-height 2))}
                   ^:prefix {:transform "translateY(-50%)"})]
         ;; Collapsible tags input etc.
         [:.collapse {:width (px header-width)
                      :position :absolute
                      :left 0
                      :padding {:left (px header-padding)
                                :right (px header-padding)
                                :bottom (px header-padding)}
                      :background-color dark-background
                      :line-height (em 1)}
          [:&:before (list
                      {:content (pr-str " ")
                       :display :block
                       :width "100%", :height (px collapse-height)
                       :background {:image (image-url "dropdown-arrow.svg")
                                    :position [["50%" "50%"]]
                                    :repeat :no-repeat}
                       :z-index 100}
                      ^:prefix {:transform "scale(1,-1)"})]
          [:&.collapsed {:height (px collapse-height)
                         :padding-bottom (px 2)}
           [:&:before ^:prefix {:transform "scale(1,1)"}]]
          [:&.open {:height :auto}]]])
      [:ul.pages {:margin 0, :padding 0}
       [:li.page (list
                  {:width "100%"
                   :list-style-type :none}
                  ^:prefix {:user-select :none})

        page-css
        
        [:&.dragging {:display :none}]
        ;; Bars for drop targets above/below
        [:&.above :&.below {:position :relative}
         (let [bar-height 20
               x {:content (pr-str "")
                  :display :block
                  :width "100%"
                  :height (px bar-height)
                  :background {:image (image-url "insertion.svg")
                               :repeat :no-repeat
                               :position [[:center :center]]}
                  :position :absolute
                  :left 0}
               offset (px (- (/ bar-height 2)))]
           (list
            [:&.above [:&:before (assoc x :top offset)]]
            [:&.below [:&:after  (assoc x :bottom offset)]]))]
        [:&.selected {:position :relative}
         [:&:before {:content (pr-str " ")
                     :display :block
                     :width "100%"
                     :height "100%"
                     :position :absolute
                     :top 0 :left 0
                     :background-color "rgba(0,0,1,0.5)"}]]
        [:img {:max-width "100%"
               :max-height "100%"}]]]

      [:footer {:display :none}]
      [:&.footer-visible (calc-property :height
                                        ["100%"
                                         - header-height
                                         - collapse-height
                                         - footer-height])
       [:footer {:position :absolute
                 :height (px footer-height)
                 :width "100%"
                 :background-color header-color
                 :bottom 0
                 :display :initial}
        [:button (list
                  {:position :relative
                   :left "50%", :top "50%"
                   }
                  ^:prefix {:transform "translate(-50%,-50%)"})]]]])
   ;; The drop-area to create new documents
   [:td.create-document {:min-width (px document-width)
                         :position :relative
                         :display :block}
    [:&.active {:background-color "lightgray"}]
    [:.note (list
             {:width "100%"
              :height "100"
              :position :absolute
              :top "50%", :left "50%"
              :text-align :center}
             ^:prefix {:transform "translate(-50%, -50%)"})
     [:.arrow {:background {:image (image-url "drop-arrow.svg")
                            :repeat :no-repeat
                            :position :center
                            :size (px 72)}
               :width "100%"
               :height (px 72)}]
     ;; [:img {:width (px 72)
     ;;        :width "100%"
     ;;        :height (px 72)
     ;;        :position :relative}]
     (let [font-size 12]
       [:p {:font {:size (pt font-size)
                   :style :italic
                   :weight 100}
            :line-height (pt (+ font-size 4))
            :width (px 150)
            :color grey-2}])]]])

;;; Dashboard
(def dashboard-css
  (let [document-width 200
        title-height 20
        tags-height 40
        preview-height (/ document-width x43-ratio)
        document-height (+ preview-height
                           title-height
                           tags-height)
        document-padding 20
        page-padding 10]
    [:&.dashboard {:background-color light-background}
     [:.pane {:float :left
              :height "100%"}
      [:header
       [:.document-count {:font-size (pt 9)
                          :padding-left (px 5)}]]
      [:.documents (list
                    {:overflow-y :auto}
                    (calc-property :height ["100%" - header-height]))
       [:.document {:display :inline-block
                    :height (px document-height)
                    :width (px document-width)
                    :padding (px document-padding)}
        [:&:hover {:background-color dark-background}]

        page-css
        
        [:.preview {:height (px preview-height)
                    :border {:width (px 1)
                             :style :solid
                             :color border-light}
                    :background-color light-background
                    :position :relative
                    :overflow :hidden}
         [:img (list
                {:max-height "100%"
                 :padding {:left (px page-padding)
                           :right (px page-padding)}}
                (calc-property :max-width ["100%" - (* 2 page-padding)]))]
         (let [margin 10]
           [:.page-count {:position :absolute
                          :right (px margin)
                          :bottom (px margin)
                          :font-size (pt 9)
                          :color dashboard-page-count-color}])]
        (let [title-padding 5
              title-height (em 2)]
          [:.title {:width "100%"
                    :display :block
                    :padding {:top (px title-padding)
                              :bottom (px title-padding)}
                    :height title-height
                    :line-height title-height
                    :overflow :hidden
                    :white-space :nowrap
                    :text-overflow :ellipsis}])
        [:.tags {:width "100%"}]]]
      [:.sidebar {:height "100%"
                  :background-color dark-background
                  :border-left (str "1px solid " border-dark)
                  :position :relative}
       (draggable-css :left)]]]))

;; Single Document
(def document-css
  [:&.document
   [:header {:overflow-x :hidden
             :vertical-align :top
             :white-space :nowrap
             :text-overflow :ellipsis}
    [:form
     [:button (list
               {:float :right
                :margin-top (px (/ header-height 2))}
               ^:prefix {:transform "translateY(-50%)"})]]]
   [:.pane {:float :left
            :height "100%"
            :overflow :auto}
    [:.thumbnails :.full :.sidebar {:height "100%"
                                    :border-right (str "1px solid " border-dark)}
     page-css
     [:header {:font-weight 500}]
     [:ul.pages (list
                 (calc-property :height ["100%" - header-height])
                 {:overflow :auto
                  :margin 0, :padding 0})
      [:li
       [:img {:max-width "100%"
              :border (str "1px solid " border-light)}]]]]
    ;; Page Thumbnails
    [:.thumbnails
     [:header {:cursor :pointer}]
     [:ul.pages {:counter-reset "page-counter"}
      (let [padding 20]
        [:li {:padding (px padding)}
         [:&:before {:content "counter(page-counter)"
                     :counter-increment "page-counter"
                     :text-align :center
                     :font-size (pt 10)
                     :display :block
                     :margin-bottom (px 10)}]
         (let [border (str "1px solid " border-light)]
           ;; Padding: Make room for the 1px border
           [:&.current {:padding {:top (px (dec padding))
                                  :bottom (px (dec padding))}
                        :border {:top border, :bottom border}
                        :background light-background}
            [:img {:border "1px solid transparent"
                   :box-shadow (str "0px 0px 3px" blue-text)}]])])]]
    ;; Full Page View
    [:.full
     [:header {:text-align :right}]
     [:ul.pages {:min-width (px 400)}
      [:li
       (let [page-margin 20, page-border 1]
         [:img (list {:margin {:left (px page-margin)
                               :right (px page-margin)
                               :top (px (/ page-margin 2))}
                      :border-width (px page-border)}
                     (calc-property :max-width ["100%" - (* 2 page-margin) - (* 2 page-border)]))])]]]

    [:.sidebar {:background-color sidebar-color}
     [:header {:text-align :center
               :font-style :italic}]
     ;; Meta Data Table
     [:aside {:padding {:left (px 25)
                        :right (px 25)}}]
     [:ul.meta {:font-size (pt 10)
                :line-height (pt 15)
                :padding 0
                :list-style "none"}
      [:span {:display :inline-block}
       [:&.title {:font-weight 500
                  :height "1em"
                  :width "30%"}
        [:&:after {:content (pr-str ": ")}]]
       (let [left-padding 8]
         [:&.value (list
                    {:padding-left (px left-padding)
                     :white-space :nowrap
                     :overflow :hidden
                     :vertical-align :top
                     :text-overflow :ellipsis}
                    (calc-property :width ["70%" - left-padding]))])]]]]])

(def workflow-css
  [:.workflow {:height "100%"
               :overflow :auto}
   generic-header-css

   dashboard-css
   document-css
   inbox-css])

;;; Tags

(def tags-min-height 20)

(def tags-css
  (let [tag-icon-box 24]
    [:ul.tags {:padding 0, :margin 0
               :font-size (pt 8)
               :overflow-x :hidden
               :white-space :nowrap
               :min-height (px tags-min-height)
               :line-height (px (+ 2 tags-min-height))}
     [:&.editable (list
                   {:padding-left (px tag-icon-box)
                    :border (str "1px solid " border-dark)
                    :height :auto
                    :background {:image (image-url "tag-icon.svg")
                                 :repeat :no-repeat
                                 :size (px 14)
                                 :position [[(px 4) (px 4)]]
                                 :color :white}
                    :white-space :initial}
                   (calc-property :width ["100%" -  (px tag-icon-box)])
                   ^:prefix {:border-radius (px 3)})
      [:&:before {:content (pr-str "Tags:")
                  :font-size (em 1.2)}]]
     (let [tag-height 16]
       [:li.tag {:display :inline-block
                 :background-color tags-background
                 :height (px tag-height)
                 :padding {:left (px 4)
                           :right (px 4)}
                 :margin {:left (px 2)
                          :right (px 2)}
                 :color tags-text
                 :line-height (px tag-height)
                 :text-decoration :none
                 :outline :none
                 :border-radius (em 0.5)}
        [:&.selected {:background-color tags-selected-background
                      :border (str "1px solid" border-dark)
                      :padding {:left (px 3)
                                :right (px 3)}}]
        (let [color-size 8]
          [:.color (list
                    {:display :inline-block
                     :height (px color-size)
                     :width (px color-size)
                     :margin-right (px 2)
                     ;; Border looks horrible on non-high-DPI screens
                     ;; :border "1px solid white"
                     }
                    ^:prefix {:border-radius "50%"})])])
     (let [input-padding 5]
       [:li {:display :inline-block}
        [:input {:background-color :transparent
                 :border :none
                 :min-width (px 10)
                 :width (px 60)
                 :padding {:left (px input-padding)
                           :right (px input-padding)}}
         [:&:focus {:outline 0}]]])]))

(def button-css
  (let [padding (px 10)]
    [:button :a.button
     (list
      {:background "linear-gradient(to bottom, #ffffff 0%,#e4e4e4 100%)"
       :border (str "1px solid " border-buttons)
       :color blue-text
       :text-shadow "1px 1px #ffffff"
       :height (px 25)
       :font {:family default-font
              :size (px 14)
              :weight 400}
       :outline :none
       :padding {:left padding
                 :right padding}}
      ^:prefix {:border-radius (px 3)
                :box-shadow "0px 0px 3px #dddddd"})
     [:&:hover {:background "linear-gradient(to bottom, #f5f5f5 0%,#d0d0d0 100%)"}]
     [:&:active {:box-shadow "inset 0 0 3px #000000;"}]
     [:&:disabled :&.disabled {:background "linear-gradient(to bottom, #f5f5f5 0%,#d0d0d0 100%)"
                               :color text-color-disabled}]]))

(def dropdown-css
  (let [font-size (px 14)
        height 25]
    [:label.dropdown (list {:background "linear-gradient(to bottom, #ffffff 0%,#e4e4e4 100%)"
                            :font-size font-size
                            :position :relative
                            :height (px height)
                            :text-shadow "1px 1px #ffffff"
                            :line-height (px height)
                            :display :inline-flex
                            :padding  {:left (px 10)
                                       :right (px 0)
                                       :top (px 0)
                                       :bottom (px 0)}
                            :border (str "1px solid " border-buttons)
                            :border-radius (px 4)}
                           ^:prefix {:box-shadow "0px 0px 3px #dddddd"})
     [:span {:padding {:left (px 20)
                       :right (px 20)}
             :font-weight 300}]
     [:select (list {:font-weight 500
                     :border {:top :none
                              :bottom :none
                              :right :none
                              :left (str "1px solid " border-buttons)}
                     :border-top {:right-radius (px 3)
                                  :left-radius (px 0)}
                     :border-bottom {:right-radius (px 3)
                                     :left-radius (px 0)}
                     :text-shadow "1px 1px #ffffff"
                     :font-size font-size
                     :background (str (image-url "dropdown-arrow.svg") " 10px center no-repeat")
                     :color blue-text
                     :outline :none
                     :font-family default-font
                     :padding {:left (px 30)
                               :right (px 10)}}
                    ^:prefix {:appearance :none})
      [:&:active {:background-gradient :white}]
      [:&:disabled (list {:color text-color-disabled}
                         ^:prefix {:filter "grayscale(100%)"})]
      [:&:hover {:background [(str (image-url "dropdown-arrow.svg") " 10px center no-repeat")
                              "linear-gradient(to bottom, #f5f5f5 0%,#d0d0d0 100%)"] }]]]))

(def upload-css
  (let [padding 15
        header-height 15
        upload-button-height 40]
    [:#upload {:background-color dark-background
               :border (str "1px solid " border-dark)
               :width "40%", :height "40%"
               :min-height (px 220)
               :min-width (px 450)
               :position :absolute
               :bottom 0, :right 0
               :z-index 1000
               :font-size (pt 10)
               :overflow :hidden}
     (let [mini-height 30]
       [:&.mini {:height (px mini-height)
                 :line-height (px mini-height)
                 :width (px 100)
                 :min-width 0, :min-height 0
                 :font {:size (pt 9)}
                 :text-align :center
                 :cursor :pointer}])
     ;; Header, clickable to minify
     [:header {:width "100%", :height (px 15)
               :position :absolute, :top 0
               :background-color darker-background
               :cursor :pointer}]
     [:ul.files (list
                 {:padding {:left (px 10)}
                  :margin {:top (px header-height)}
                  :overflow-y :auto
                  :list-style-type :none}
                 (calc-property :height ["100%" - header-height - upload-button-height]))
      (let [height 30]
        [:li {:height (px height)
              :line-height (px height)
              :position :relative}
         [:.title {:max-width "70%", :height "100%"
                   :display :inline-block
                   :overflow :hidden
                   :vertical-align :top
                   :white-space :nowrap
                   :text-overflow :ellipsis}]
         [:a.title {:text-decoration :underline}]
         [:.size {:font-size (pt 8)
                  :padding (px 4)}]
         [:.upload :.hide (list
                           {:position :absolute
                            :top "50%"
                            :right 0}
                           ^:prefix {:transform "translateY(-50%)"})]
         [:.hide {:text-decoration :underline
                  :cursor :pointer
                  :display :block}
          [:&:hover {:color grey-1}]]])]
     [:form.upload {:width "100%", :height (px upload-button-height)}]]))

(def clear-a-css
  [:a {:text-decoration :none
       :color default-text}])

(def css-string
  (css
   {:vendors ["webkit" "moz" "ms"]
    :output-to "resources/public/style.css"}
   [:html :body :#app {:height "100%"
                       :font-weight "300"
                       :font-family default-font}]
   [:body :#app {:margin 0 :padding 0
                 :color default-text
                 :letter-spacing (px 0.8)}]
   [:#app {:height "100%"}
    [:&.file-drop
     {:background "red"}]
    clear-a-css
    button-css
    dropdown-css
    sidebar-css
    tags-css
    upload-css

    [:main {:height "100%"}
     workflow-css]]))

;;; Apply the CSS

(when-let [node (js/document.getElementById "style")]
  (while (.hasChildNodes node)
    (.removeChild node (.-lastChild node)))
  (.appendChild node (.createTextNode js/document css-string)))
