(ns solsort.touchlang.touchlang
  (:require-macros
   [cljs.core.async.macros :refer [go go-loop alt!]]
   [reagent.ratom :as ratom :refer  [reaction]]
   [solsort.toolbox.macros])
  (:require
   [cljs.reader]
   [solsort.toolbox.setup]
   [solsort.toolbox.appdb :refer [db db! db-async!]]
   [solsort.toolbox.ui :refer [input select]]
   [solsort.util
    :refer
    [<ajax <seq<! js-seq load-style! put!close!
     parse-json-or-nil log page-ready render dom->clj]]
   [reagent.core :as reagent :refer []]
   [clojure.string :as string :refer [replace split blank?]]
   [cljs.core.async :refer [>! <! chan put! take! timeout close! pipe]]))

(defn hash-color-light [s]
  (str "#"
       (-> s
           (hash)
           (bit-and 0xffffff)
           (bit-or 0x1b0b0b0)
           (.toString 16)
           (.slice 1))))

(defn styling []
  (load-style!
   (let [total-width js/window.innerWidth
         total-height js/window.innerHeight

         scrollbar-size (if (= -1 (.indexOf js/navigator.userAgent "Mobile")) 17 4)
         spacing (+ 4 (* 2 scrollbar-size))

         min-item-height 36
         item-ratio 1.666666
         items-per-width (Math.floor (/ (- total-width spacing) (* item-ratio min-item-height)))
         items-left (Math.floor (* 0.5 items-per-width))
         item-width (Math.floor (/ (- total-width spacing)
                                   items-per-width))
         bar-width (+ item-width scrollbar-size)

         item-height (/ item-width item-ratio)
         bar-height (+ item-height scrollbar-size)
         actual-spacing (Math.floor (* 0.5
                                       (- total-width
                                          (* items-per-width
                                             item-width))))
         bottom-height (* item-height
                          (Math.ceil
                           (* 0.6 (/ total-height item-height))))
         landscape (< (* 1.1 total-height) total-width)
         [main expr actions fns objs]
         (if landscape
           [{:left bar-width
             :top bar-height
             :width (- total-width (* 2 bar-width))
             :height (- total-height (* 2 bar-height))}
            {:top 0
             :left bar-width
             :height bar-height
             :right bar-width}
            {:bottom 0
             :left bar-width
             :height bar-height
             :right bar-width}
            {:top 0
             :bottom 0
             :left 0
             :text-align :right
             :width bar-width}
            {:top 0
             :bottom 0
             :text-align :left
             :right 0
             :width bar-width}]
           [{:left 0
             :top 0
             :right 0
             :bottom bottom-height}
            {:bottom (- bottom-height (* bar-height 2))
             :left 0
             :right 0
             :height bar-height}
            {:bottom (- bottom-height (* bar-height 1))
             :left 0
             :right 0
             :height bar-height}
            {:height (- bottom-height (* bar-height 2))
             :left 0
             :text-align :left
             :width (+ actual-spacing (* items-left item-width))
             :bottom 0}
            {:height (- bottom-height (* bar-height 2))
             :right 0
             :text-align :right
             :width (+ actual-spacing (* (- items-per-width items-left) item-width))
             :bottom 0}])
         [expr main fns objs actions]
         (map
          #(into %
                 {:display :inline-block
                  :position :absolute
                  :overflow :auto})
          [expr main fns objs actions])
         action-count 7
         action-size item-height
         action-hpad (- (/ (if landscape (- total-width (* 2 bar-width)) total-width) action-count) action-size)
         action-vpad 0
         entries-per-line (max 1 (js/Math.floor (/ (:width objs) 80)))
         entry-width (/ (:width objs) entries-per-line)
         entry-height (* 0.5 entry-width)]
     {"body"
      {:margin 0 :padding 0 :background :black}
      ".expr"
      (into expr
            {:background :black
             :overflow :auto
             :white-space :nowrap
             :text-align :left})
      ".innerExpr"
       {:position :absolute
        :top "50%"
        :font-size item-height
        :margin-top (* -0.5 item-height)
        :margin-left 2
        :margin-right 2
        }
      ".actions"
      (into actions
            {:background :white
             :text-align :center
             :overflow :hidden
             :vertical-align :middle
        ;:box-shadow "2px 2px 5px rgba(0,0,0,0.5)"
             :box-sizing :border-box
             :outline "1px solid black"
             :padding-top (* .5 scrollbar-size)
             ;:box-shadow "inset 0px 0px 8px 4px black"
             })
      ".actions > img"
      {:width (+ action-hpad action-size)
       :height (+ action-size action-vpad)
       :padding-top (* .5 action-vpad)
       :padding-bottom (* .5 action-vpad)
       :padding-left (* .5 action-hpad)
       :padding-right (* .5 action-hpad)
       :margin 0}
      ".fns"
      (into fns
            {:background "#000"
             :text-align :center
             :outline "1px solid black"})
      ".main"
      (into main
            {:background "#ccf"})
      ".objs"
      (into objs
            {:background "black"
             :text-align :center
             :outline "1px solid black"})
      :.entry
      {:display :inline-block
       :text-align :left
       :vertical-align :middle
       :white-space :nowrap
       :overflow :hidden
       :background :white
       :font-size (* item-height 0.3)
       :line-height (/ item-height 3)
       :width item-width
       :height item-height
       :outline "1px solid black"}})
   :styling))
(js/window.addEventListener "resize" styling)
(js/window.addEventListener "load" #(js/setTimeout styling 0))
(styling)

(deftype Number [val]
  Object
  (add [_ y] (Number. (+ val (.-val y))))
  (sub [_ y] (Number. (- val (.-val y))))
  (mul [_ y] (Number. (* val (.-val y))))
  (availableProps [_] {"inc" ["add" ["literal" 1]]
                       "dec" ["sub" ["literal" 1]]})
  (toJSON [_] #js ["value" val])
  (toString [_] (str val)))
(deftype String [val]
  Object
  (toJSON [_] #js ["value" val])
  (toString [_] (str val)))


(def world
  #js{"functions"
      (fn []
        {"literal" (fn [a b] b)})})

(defonce needs-eval (atom #{1 2 3}))
(defn update-node [i]
    (when (number? i)
      (swap! needs-eval conj i)
      (doall (for [child (filter number? (db [:graph i :args]))]
                (db! [:graph child :deps]
                     (conj (db [:graph child :deps] #{}) i))))
       (db! [:graph i :deps]
            (into
             #{}
             (filter
              #((into #{} (db [:graph % :args] #{})) i)
              (db [:graph i :deps] []))))))

(db! [:graph]
    [{:fn "Literal" :args ["34"]}
     {:fn "Literal" :args ["12"]}
     {:fn "+" :args [0 1]}
    {:fn "Literal" :args ["Hello"]}
    {:fn "+" :args [2 3]}]
    )
(doall (for [i (range (count (db [:graph])))] (update-node i)))
(doall (for [i (range (count (db [:graph])))] (update-node i)))

(db [:graph])
(defn functions [o]
  (if (fn? (.-functions o))
    (.function o)
    (cond
      (nil? o)
      {"world" (fn [] world)
       }
      (number? o)
      {"+" +
       "-" -
       "*" *
       "/" /
       }
      (string? o)
      {"Literal" #(js->clj (try (JSON.parse %)
                                (catch js/Error e %)
                                ))}
      )))

(defonce evaluating (atom false))
(defonce eval-seq (atom 0))
(defn eval-loop []
  (reset! evaluating true)

      (if (empty? @needs-eval)
     (reset! evaluating false)
     (let [i (first @needs-eval)
           ]
       (swap! needs-eval disj i) 
       (js/setTimeout eval-loop 0)
       (let [node (db [:graph i])
             args (log (map #(if (string? %) % (get (db [:graph %]) :val)) (:args node)))
             f (get (into {} (functions (first args))) (log (:fn node)) (fn [] (js/Error "Invalid function")))
             val (apply f args)

             node (if (= val (:val node))
                    node
                   (into node
                         {:val val
                          :seq (swap! eval-seq inc)}))]
         (doall
          (for [dep (get node :deps [])]
            (when (< (db [:graph dep :seq] js/Number.POSITIVE_INFINITY) (:seq node))
              (swap! needs-eval conj dep))))
         (db! [:graph i] node)
         (log "evalled" i node))
       )))

(db! [:graph 0 :args] ["999"])
(swap! needs-eval conj 0)
(eval-loop)


(defn execute [expr]
  (js/console.log 'execute expr))
(defn begin-form [id]
  (js/console.log 'begin-form id))

(defn action-button [id f]
  [:img.icon
   {:src (str "assets/icons/noun_" id ".svg")
    :on-click f}])

(defn expr []
  [:div.expr
   [:div.innerExpr
    [:div.entry
     "result"]
    [:b {:style {:font-size 30
                 :color :white}
         :vertical-align :middle}
     "="]
    [:div.entry "o0"]
    [:b {:style {:font-size 30 :color :white} :vertical-align :middle} "."]
    [:div.entry "function"]
    [:b {:style {:font-size 30 :color :white} :vertical-align :middle} "("]
    [:div.entry "o1"]
    [:div.entry "o2"]
    [:div.entry "on"]
    [:b {:style {:font-size 30 :color :white} :vertical-align :middle} ")"]]
   ])

(log (db [:data]))
(defn main []
  [:div.main
   (cond
     (= (db [:ui :input]) :string)
     [:form
      {:on-submit
       (fn [e]
         (.preventDefault e)
         (let [val (db [:ui :value] "")]
           (db! [:data]
                (conj (db [:data] [])
                      {:code val
                       :val val})))
         (db! [:ui :input]))}
      [:textarea
       {:auto-focus true
        :on-change
        (fn [e]
          (db! [:ui :value] (String. (-> e (.-target) (.-value)))))}]
      [:input
       {:type :submit}]]
     (= (db [:ui :input]) :number)
     [:form
      {:on-submit
       (fn [e]
         (.preventDefault e)
         (let [val (db [:ui :value] 0)]
           (db! [:data]
                (conj (db [:data] [])
                      {:code val
                       :val val})))
         (db! [:ui :input]))}
      [:input
       {:auto-focus true
        :inputmode :numeric
        :on-change (fn [e] (db! [:ui :value] (Number. (js/parseFloat (-> e (.-target) (.-value))))))}]
      #_[:input
         {:type :submit}]]
     :else [:div "obj"])])
(defn objs []
  (into
   [:div.objs]
   (reverse
    (map-indexed
     (fn [i o]
       [:div.entry
        {:on-click #(db! [:ui :current] i)
         :class (if (= i (db [:ui :current])) "current" "")}
        (JSON.stringify (clj->js (get o :code ""))) [:br] (str (get o :val ""))])
     (db [:data] [])))))
(defn fns [o]
  (into [:div.fns]
        (for [v
              (sort (remove #{"constructor"}
                            (js->clj (js/Object.getOwnPropertyNames
                                      (.-prototype (.-constructor o))))))]
          [:div.fn.entry
           {:on-click #(begin-form v)
            :style
            {:background-color (hash-color-light v)}}
           [:strong v] [:br]
           [:em "..."]])))
(defn availableProps [o]
  (sort
   (if (aget o "availableProps")
     (js->clj (.availableProps o))
     (for [name (js->clj (js/Object.getOwnPropertyNames o))]
       [name ["get" name]]))))
(defn props [o]
  (into [:div.props]
        (for [[k v] (availableProps o)]
          [:div.fn
           {:on-click #(execute v)
            :style
            {:background-color (hash-color-light k)}}
           [:strong k] [:br]
           [:em (str v)]])))
(defn actions []
  [:div.actions
   #_[action-button 593402
      (fn []
        (db! [:ui :current] (count (db [:data] [])))
        (db! [:ui :input] :number))]
   [action-button 605398
    (fn []
      (db! [:ui :layout] (inc (db [:ui :layout] 0)))
      (js/setTimeout styling 0))]
   [action-button "47250_num"
    (fn []
      (db! [:ui :current] (count (db [:data] [])))
      (db! [:ui :input] :number))]
   [action-button 47250
    (fn []
      (db! [:ui :current] (count (db [:data] [])))
      (db! [:ui :input] :string))]
   [action-button "209279_rotate" #(js/console.log "fn")]
   [action-button 593402 #(js/console.log "world")]
   [action-button 684642 #(js/console.log "delete")]
   [action-button 619343 #(js/console.log "ok")]])
(defn ui []
  (let [obj (db [:data (db [:ui :current] -1)] {})
        val (get obj :val #js {})]
    [:div
     [main obj]
     [props val]
     [fns val]
     [objs]
     [expr]
     [actions]]))
(render [ui])
