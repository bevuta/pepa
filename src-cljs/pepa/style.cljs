(ns pepa.style
  (:require [garden.core :refer [css]]
            [garden.units :as u :refer [px em pt]]
            [garden.stylesheet :refer [at-keyframes cssfn]]
            
            [nom.ui :as ui]
            [pepa.navigation :refer [navigation-elements]]

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
(def dashboard-selection-color "#bedbff")

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
    [:header {:min-height (px header-height)
              :max-height (px header-height)
              :line-height (px header-height)
              :z-index 110
              :background-color header-color
              :padding {:left (px header-padding)
                        :right (px header-padding)}}]))

(def draggable-size 24)
(defn draggable-css [position]
  [:.draggable (assoc
                {:position :absolute
                 :top (px 0)
                 :width (px draggable-size)
                 :height (px draggable-size)
                 :z-index 120
                 :cursor :ew-resize
                 :background {:image (image-url "resize.svg")
                              :size [(px draggable-size)
                                     (px draggable-size)]}
                 :opacity 0.5}
                (or position :left)
                (px (if (= position :right)
                      0
                      (* -1 draggable-size))))
   [:&:hover {:opacity 1.0}]])

(def sidebar-header-css
  (list
   generic-header-css
   [:header {:text-decoration :none
             :font-size (em 1.2)
             :display :flex
             :flex-direction :row
             :align-items :center}

    ;; Logo
    (let [logo-width 38]
      [:.logo {:width (px logo-width)
               :height (px logo-width)}])
    [:span {:padding-left (px 5)}
     [:&.brand {:font-weight 400}]]]))

(defn sidebar-search-css [height]
  (let [search-margin-horizontal 20
        search-margin-vertical 25
        search-input-padding 5]
    [:.search {:height (px height)
               :position :relative
               :display :flex
               :align-items :center
               :justify-content :space-around}
     [:input {:margin {:left (px search-margin-horizontal)
                       :right (px search-margin-horizontal)
                       :top (px search-margin-vertical)
                       :bottom (px search-margin-vertical)}
              :padding (px search-input-padding)
              :padding-right (px (* 5 search-input-padding))
              :width "100%"
              :color default-text             
              :background {:image (image-url "glass.svg")
                           :repeat :no-repeat
                           :position "center right"}
              :height (px (- height (+ (* 2 search-margin-vertical) (* 2 search-input-padding))))
              :border-radius (px search-input-padding)}]]))

(def sidebar-css
  (let [search-height 80]
    [:#sidebar {:height "100%"
                :display :flex
                :background-color sidebar-color
                :position :relative
                :flex-direction :column}
     (draggable-css :right)
     sidebar-header-css
     (sidebar-search-css search-height)

     ;; Sections
     [:nav.workflows (list
                      {:overflow-y :auto}
                      (calc-property :height ["100%"
                                              - (+ search-height header-height)
                                              - 1 ; border
                                              ]))
      [:ul {:padding-left 0, :margin 0}
       (let [item-height 50
             padding (/ item-height 8)
             line-height (/ item-height 1.5)
             icon-size 20
             padding-left 20]
         [:li {:list-style-type :none
               :height (px line-height)
               :line-height (px line-height)
               :padding {:top (px padding)
                         :bottom (px padding)}
               :background {:repeat :no-repeat
                            :size (px icon-size)
                            :position [[(px padding-left) :center]]}
               :user-select :none}
          (for [row (map (comp name second) navigation-elements)]
            (let [selector (keyword (str "&." row))]
              [selector
               [:.menu-link
                [:.title
                 [:&:before {:background-image (image-url (str "menu-icons/" row ".svg"))}]]]
               [:&.active
                [:.menu-link
                 [:.title
                  [:&:before :&:after {:background-color blue-background}]
                  [:&:before {:background-image (image-url (str "menu-icons/" row "-active.svg"))}]]]]]))
          [:&.inbox {:position :relative}
           [:.drop-target {:background-color darker-background}]
           [:.count {:position :absolute
                     :right (px 26)
                     :font-size (pt 11)
                     :color blue-text}]]
          [:&.tags
           ;; Use different icon when open?
           ;; [:div.open
           ;;  [:&:before {:background-image (image-url "menu-icons/tags-open.svg")}]]
           [:ul :.show-more {:padding-left (px (+ (* 2 padding-left)
                                                  icon-size))}]
           [:ul {:list-style-type :none
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
               [:.tag-name {:position :absolute
                            :top "50%" :transform "translateY(-50%)"
                            :overflow :hidden
                            :text-overflow :ellipsis}]
               [:.count {:float :right
                         :padding {:left (px 4), :right (px 4)}}]
               [:.color {:width (px color-size)
                         :height (px color-size)
                         :float :right}]])]
           [:.show-more {:font-weight :bold
                         :font-size (px 10)
                         :cursor :pointer}]]
          
          [:.menu-link
           [:.title {:height (px line-height)}
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
   (let [rotate-height 24]
     [:.rotate {:position :absolute
                :top 0, :right 0
                :height (px rotate-height)
                :z-index 1
                :background-color "rgba(255, 255, 255, 0.8)"
                :margin (px 5)
                :border-radius (px 4)}
      [:.left :.right {:float :right
                       :width (px rotate-height)
                       :height (px rotate-height)
                       :line-height (px rotate-height)
                       :text-align :center
                       :cursor :pointer
                       :opacity 0.3
                       :user-select :none}
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
  [:&.inbox {:overflow :auto
             :display :flex
             :flex-direction :row}
   [:.pane {:height "100%"
            :min-width (px document-width), :max-width (px document-width)
            :display :flex
            :flex-direction :column
            :flex-grow 1}
    [:header {:display :flex
              :flex-flow [[:row :wrap]]
              :align-items :center
              :justify-content :space-between}]]
   ;; Create Document Column
   [:.create-document {:align-items :center
                       :justify-content :space-around}
    [:.note
     [:.arrow {:background {:image (image-url "drop-arrow.svg")
                            :repeat :no-repeat
                            :position :center
                            :size (px 72)}
               :height (px 72)}]]]
   ;; A 'Document' (with pages inside)
   [:.document
    [:header {:max-height :initial}
     [:.tags {:margin-bottom (px 5)}]]
    
    [:ul.pages {:margin 0, :padding 0
                :overflow-y :auto
                :flex-shrink 999}
     [:li.page {:width "100%"
                :list-style-type :none
                :user-select :none}
      ;; TODO: Move this into a generic css rule
      [:&.selected
       [:.thumbnail {:position :relative}
        [:&:before {:content (pr-str " ")
                    :display :block
                    :width "100%", :height "100%"
                    :position :absolute
                    :top 0 :left 0
                    :background-color "rgba(0,0,1,0.5)"
                    :z-index 10}]]]
      page-css
      [:img {:max-width "100%"
             :max-height "100%"}]]]
    [:barter {:min-height (px 40)
              :max-height (px 40)
              :display :flex
              :flex-direction :row
              :justify-content :space-around
              :align-items :center
              :background-color header-color}]]])

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
     [:.pane {:height "100%"
              :display :flex
              :flex-direction :column}
      [:header {:flex [[1 "100%"]]}
       [:.document-count {:font-size (pt 9)
                          :padding-left (px 5)}]]
      [:.documents {:overflow-y :auto
                    :display :flex
                    :flex-flow [[:row :wrap]]
                    :justify-content :space-around}
       [:&.working {:opacity "0.2"}]
       [:.document {:height (px document-height)
                    :width (px document-width)
                    :padding (px document-padding)
                    :cursor :pointer}
        [:&:hover {:background-color dark-background}]
        [:&.selected {:background-color dashboard-selection-color}]

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
                  :width "100%"
                  :background-color dark-background
                  :position :relative}
       (draggable-css :left)]]]))

;; Single Document
(def document-css
  [:&.document
   [:header {:overflow-x :hidden
             :vertical-align :top
             :white-space :nowrap
             :text-overflow :ellipsis}
    [:form {:display :inline-flex
            :align-items :center
            :justify-content :space-between
            :width "100%"
            :height "100%"}
     [:input {:flex-grow 999
              :min-width 0
              :margin-right (px 10)}]]]
   [:.pane
    [:&>div {:height "100%"
             :display :flex
             :flex-direction :column}
     [:ul.pages {:overflow :auto
                 :outline :none
                 :margin 0, :padding 0}
      page-css
      [:li
       [:img {:max-width "100%"
              :border (str "1px solid " border-light)}]]]]
    ;; Page Thumbnails
    [:.thumbnails {:position :relative} ; :relative for `draggable-css'
     [:header {:cursor :pointer}]
     (draggable-css :right)
     ;; Counter implementation
     [:ul.pages {:counter-reset "page-counter"}
      (let [padding 20]
        [:li {:padding (px padding)}
         [:&:before {:content "counter(page-counter)"
                     :counter-increment "page-counter"
                     :display :block
                     :text-align :center
                     :font-size (pt 10)
                     :margin-bottom (px 10)}]
         (let [border (str "1px solid " border-light)]
           ;; Padding: Make room for the 1px border
           [:&.current {:padding {:top (px (dec padding))
                                  :bottom (px (dec padding))}
                        :border {:top border, :bottom border}}
            [:img {:border "1px solid transparent"
                   :box-shadow (str "0px 0px 3px" blue-text)}]])])]]
    ;; Full Page View
    [:.full
     [:header {:text-align :right}]
     [:ul.pages #_{:min-width (px 400)}
      [:li
       (let [page-margin 20, page-border 1]
         [:img (list {:margin {:left (px page-margin)
                               :right (px page-margin)
                               :top (px (/ page-margin 2))}
                      :border-width (px page-border)}
                     (calc-property :max-width ["100%" - (* 2 page-margin) - (* 2 page-border)]))])]]]

    [:.sidebar {:background-color sidebar-color
                :position :relative}
     [:header {:text-align :center
               :font-style :italic}]
     (draggable-css :left)]]])

(def generic-input-css
  (let [padding (px 5)
        height (px 14)
        radius (px 3)]
    [:input {
             :padding padding
             :color default-text
             :outline :none
             :font {:family default-font
                    :size (px 13)
                    :weight 400}
             :height height
             :border-radius radius
             :border (str "1px solid " border-dark)}]))

(def generic-sidebar-css
  [:.sidebar
   [:aside {:padding {:left (px 25)
                      :right (px 25)}}
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
                   (calc-property :width ["70%" - left-padding]))])]]]])

(def workflow-css
  [:.workflow {:height "100%" :width "100%"
               :overflow-x :hidden
               :display :flex
               :flex-direction :row}
   [:.pane {:flex-grow 1
            ;; Firefox workaround. necessry for stuff to shrink below
            ;; their intrinsic width
            :min-width 0    
            :height "100%"}]
   generic-header-css
   generic-sidebar-css
   
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
                    :border-radius (px 3)
                    :height :auto
                    :background {:image (image-url "tag-icon.svg")
                                 :repeat :no-repeat
                                 :size (px 14)
                                 :position [[(px 4) (px 4)]]
                                 :color :white}
                    :white-space :initial}
                   (calc-property :width ["100%" -  (px tag-icon-box)]))
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
          [:.color {:display :inline-block
                    :height (px color-size)
                    :width (px color-size)
                    :margin-right (px 2)
                    ;; Border looks horrible on non-high-DPI screens
                    ;; :border "1px solid white"
                    :border-radius "50%"
                    }])])
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
  (let [padding (px 15)]
    [:button :a.button
     {:background "linear-gradient(to bottom, #ffffff 0%,#f5f5f5 100%)"      
      :color grey-3
      :height (px 25)
      :font {:family default-font
             :size (px 14)
             :weight 400}
      :outline :none
      :border "1px solid #c7c7c7"
      :padding {:left padding
                :right padding}
      :border-radius (px 2)}
     [:&:hover {:background "linear-gradient(to bottom, #f5f5f5 0%,#e5e5e5 100%)"}]
     [:&:active {:box-shadow "inset 0 0 3px #000000;"}]
     [:&:disabled :&.disabled {:opacity .5}]]))


(def dropdown-css
  (let [font-size (px 14)
        height 25]
    [:label.dropdown {:background "linear-gradient(to bottom, #ffffff 0%,#e4e4e4 100%)"
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
                      :border-radius (px 4)
                      :box-shadow "0px 0px 3px #dddddd"}
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
                               :right (px 10)}
                     :appearance :none})
      [:&:active {:background-gradient :white}]
      [:&:disabled {:color text-color-disabled
                    :filter "grayscale(100%)"}]
      [:&:hover {:background [(str (image-url "dropdown-arrow.svg") " 10px center no-repeat")
                              "linear-gradient(to bottom, #f5f5f5 0%,#d0d0d0 100%)"] }]]]))

(def upload-css
  (let [padding 10
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
                 {:padding {:left (px padding), :right (px padding)}
                  :margin {:top (px header-height)}
                  :overflow-y :auto
                  :list-style-type :none}
                 (calc-property :height ["100%" - header-height - upload-button-height]))
      (let [height 30]
        [:li {:height (px height)
              :line-height (px height)
              :position :relative}
         (let [icon-size 20]
           [:&.invalid
            [:&:before {:content (pr-str " ")
                        :display :inline-block
                        :width (px icon-size) :height (px height)
                        :background {:image (image-url (str "material/warning-invalid-file.svg?" (gensym)))
                                     :position :center
                                     :repeat :no-repeat}}]
            [:.title {:color :red
                      :padding-left (px 4)}]])
         [:.title {:max-width "70%", :height "100%"
                   :display :inline-block
                   :overflow :hidden
                   :vertical-align :top
                   :white-space :nowrap
                   :text-overflow :ellipsis}]
         [:a.title {:text-decoration :underline}]
         [:.size {:font-size (pt 8)
                  :padding (px 4)}]
         [:.progress :.hide {:position :absolute
                             :display :block
                             :right 0, :top "50%"
                             :transform "translateY(-50%)"}]
         (let [progress-height 4]
           [:.progress {:width (px 150)
                        :height (px progress-height)}
            [:.bar {:height "100%"
                    :float :right
                    :background-color "darkgrey"}]])
         [:.hide {:text-decoration :underline
                  :cursor :pointer
                  :user-select :none}
          [:&:hover {:color grey-1}]]])]
     [:form.upload {:width "100%", :height (px upload-button-height)
                    :padding {:left (px padding)
                              :right (px padding)}}]]))

(def clear-a-css
  [:a {:text-decoration :none
       :color default-text}])

(def auto-prefix #{:transition
                   :user-select
                   :border-radius
                   :pointer-events
                   :transform
                   :appearance
                   :box-shadow
                   :filter
                   ;; All the flexboxiness!
                   :flex
                   :flex-direction
                   :flex-flow
                   :flex-wrap
                   :flex-grow
                   :flex-shrink
                   :flex-basis
                   :align-items
                   :justify-content})

(def css-string
  (css
   {:vendors ["webkit" "moz" "ms"]
    :output-to "resources/public/style.css"
    :auto-prefix auto-prefix
    :auto-value-prefix {:display #{:flex :inline-flex}}}
   [:html :body :#app {:height "100%"
                       :font-weight "300"
                       :font-family default-font}]
   [:body :#app {:margin 0 :padding 0
                 :color default-text
                 :letter-spacing (px 0.8)}]
   [:#app {:height "100%"}
    [:.container {:height "100%"
                  :display :flex
                  :flex-direction :row}

     ;;this is the HEADER SHADOW ELEMENT
     ;; it has to be the before element of the .container,
     ;; because the header is splitted in three parts.
     ;; and there are some bugs with the z-index and positioning
     [:&:before {:content (pr-str " ")
                 :position :absolute ;absolute not fixed due to safari scroll special behaviour
                 :box-shadow "0px 0px 8px 0px rgba(0,0,0,0.4)"
                 :left (px 0)
                 :right (px 0)
                 :top (px 0)
                 :z-index 20
                 :pointer-events :none
                 :height (px (- header-height 2))
                 :background "transparent)"
                 }]]
    [:&.file-drop
     {:background "red"}]
    clear-a-css
    button-css
    generic-input-css
    
    dropdown-css
    sidebar-css
    tags-css
    upload-css

    [:main {:height "100%", :width "100%"
            :overflow-x :hidden}
     workflow-css]]))

;;; Apply the CSS

(when-let [node (js/document.getElementById "style")]
  (while (.hasChildNodes node)
    (.removeChild node (.-lastChild node)))
  (.appendChild node (.createTextNode js/document css-string)))
