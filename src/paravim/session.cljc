(ns paravim.session
  (:require [paravim.buffers :as buffers]
            [paravim.constants :as constants]
            [clara.rules :as clara]
            [clarax.rules :as clarax]
            [clojure.string :as str]
            #?(:clj  [clarax.macros-java :refer [->session]]
               :cljs [clarax.macros-js :refer-macros [->session]]))
  #?(:cljs (:require-macros [paravim.session :refer [->session-wrapper]])))


(defrecord Game [total-time delta-time context])
(defrecord Window [width height])
(defrecord Mouse [x y])
(defrecord MouseHover [target cursor mouse])
(defrecord MouseClick [button])
(defrecord TextBox [id left right top bottom])
(defrecord BoundingBox [id x1 y1 x2 y2 align])
(defrecord Font [size])
(defrecord CurrentTab [id])
(defrecord NewTab [id])
(defrecord CurrentBuffer [id])
(defrecord Tab [id buffer-id])
(defrecord Buffer [id tab-id
                   text-entity parinfer-text-entity
                   parsed-code needs-parinfer?
                   camera camera-x camera-y
                   path file-name
                   lines clojure?
                   cursor-line cursor-column
                   font window])
(defrecord Constants [base-rect-entity
                      base-rects-entity
                      font-width
                      font-height
                      base-font-entity
                      base-text-entity
                      roboto-font-entity
                      roboto-text-entity
                      toolbar-text-entities
                      highlight-text-entities])
(defrecord State [])

(defn change-font-size! [{:keys [size] :as font} diff]
  (let [new-val (+ size diff)]
    (when (<= constants/min-font-size new-val constants/max-font-size)
      (clarax/merge! font {:size new-val}))))

(defn font-dec! [font]
  (change-font-size! font (- constants/font-size-step)))

(defn font-inc! [font]
  (change-font-size! font constants/font-size-step))

(defn reload-file! [buffer pipes current-tab]
  (let [{:keys [out-pipe]} pipes
        {:keys [lines file-name clojure?]} buffer]
    (when (and clojure? (= current-tab :files))
      (doto out-pipe
        (.write (str "(do "
                     (pr-str '(println))
                     (pr-str (list 'println "Reloading" file-name))
                     (str/join \newline lines)
                     ")\n"))
        .flush)
      true)))

(def queries
  '{:get-game
    (fn []
      (let [game Game]
        game))
    :get-window
    (fn []
      (let [window Window]
        window))
    :get-mouse
    (fn []
      (let [mouse Mouse]
        mouse))
    :get-current-tab
    (fn []
      (let [current-tab CurrentTab]
        current-tab))
    :get-current-buffer
    (fn []
      (let [current-buffer CurrentBuffer]
        current-buffer))
    :get-tab
    (fn [?id]
      (let [tab Tab
            :when (= (:id tab) ?id)]
        tab))
    :get-mouse-hover
    (fn []
      (let [mouse-hover MouseHover]
        mouse-hover))
    :get-font
    (fn []
      (let [font Font]
        font))
    :get-bounding-box
    (fn [?id]
      (let [bounding-box BoundingBox
            :when (= (:id bounding-box) ?id)]
        bounding-box))
    :get-text-box
    (fn [?id]
      (let [text-box TextBox
            :when (= (:id text-box) ?id)]
        text-box))
    :get-buffer
    (fn [?id]
      (let [buffer Buffer
            :when (= (:id buffer) ?id)]
        buffer))
    :get-state
    (fn []
      (let [state State]
        state))
    :get-constants
    (fn []
      (let [constants Constants]
        constants))})

(def rules
  '{:mouse-hovers-over-text
    (let [window Window
          mouse Mouse
          mouse-hover MouseHover
          :when (not= mouse (:mouse mouse-hover))
          current-tab CurrentTab
          :when (not= nil (:id current-tab))
          font Font
          {:keys [id left right top bottom] :as text-box} TextBox
          :when (and (= id (:id current-tab))
                     (<= left (:x mouse) (- (:width window) right))
                     (<= (top (:height window) (:size font))
                         (:y mouse)
                         (bottom (:height window) (:size font))))]
      (clarax/merge! mouse-hover {:target :text
                                  :cursor :ibeam
                                  :mouse mouse}))
    :mouse-hovers-over-bounding-box
    (let [window Window
          mouse Mouse
          mouse-hover MouseHover
          :when (not= mouse (:mouse mouse-hover))
          font Font
          {:keys [x1 y1 x2 y2 align] :as bounding-box} BoundingBox
          :when (let [font-size (:size font)
                      game-width (:width window)
                      x1 (cond->> (* x1 font-size)
                                  (= :right align)
                                  (- game-width))
                      y1 (* y1 font-size)
                      x2 (cond->> (* x2 font-size)
                                  (= :right align)
                                  (- game-width))
                      y2 (* y2 font-size)]
                  (and (<= x1 (:x mouse) x2)
                       (<= y1 (:y mouse) y2)))]
      (clarax/merge! mouse-hover {:target (:id bounding-box)
                                  :cursor :hand
                                  :mouse mouse}))
    :mouse-clicked
    (let [game Game
          mouse-click MouseClick
          mouse-hover MouseHover
          current-tab CurrentTab
          current-buffer CurrentBuffer
          buffer Buffer
          :when (= (:id buffer) (:id current-buffer))
          font Font]
      (clara/retract! mouse-click)
      (when (= :left (:button mouse-click))
        (let [{:keys [target]} mouse-hover]
          (if (constants/tab? target)
            (clara/insert-unconditional! (->NewTab target))
            (case target
              :font-dec (font-dec! font)
              :font-inc (font-inc! font)
              :reload-file (when (reload-file! buffer (:paravim.core/pipes game) (:id current-tab))
                             (clarax/merge! current-tab {:id :repl-in}))
              nil)))))
    :tab-changed
    (let [game Game
          current-tab CurrentTab
          new-tab NewTab]
      (clara/retract! new-tab)
      (clarax/merge! current-tab {:id (:id new-tab)})
      ((:paravim.core/send-input! game) [:new-tab]))
    :update-cursor-when-font-changes
    (let [game Game
          font Font
          buffer Buffer
          :when (and (not= font (:font buffer))
                     ;; ignore ascii buffers
                     (number? (:id buffer)))
          state State
          text-box TextBox
          :when (= (:id text-box) (:tab-id buffer))
          constants Constants]
      (clarax/merge! buffer
        (-> buffer
            (buffers/update-cursor state font text-box constants game)
            (assoc :font font))))
    :update-cursor-when-window-resizes
    (let [game Game
          font Font
          window Window
          current-buffer CurrentBuffer
          current-tab CurrentTab
          buffer Buffer
          :when (and (not= window (:window buffer))
                     (or (= (:id buffer) (:id current-buffer))
                         ;; if we're in the repl, make sure both the input and output are refreshed
                         (= (:tab-id buffer) (case (:id current-tab)
                                               :repl-in :repl-out
                                               :repl-out :repl-in
                                               nil))))
          state State
          text-box TextBox
          :when (= (:id text-box) (:tab-id buffer))
          constants Constants]
      (clarax/merge! buffer
        (-> buffer
            (buffers/update-cursor state font text-box constants game)
            (assoc :window window)))
      (when (= (:id buffer) (:id current-buffer))
        ((:paravim.core/send-input! game) [:resize])))})

#?(:clj (defmacro ->session-wrapper []
          (list '->session (merge queries rules))))

(def *session (atom nil))

(defn restart! []
  (reset! *session
    (-> (->session-wrapper)
        (clara/insert
          (->Mouse 0 0)
          (->MouseHover nil nil nil)
          (->CurrentTab :files)
          (->CurrentBuffer nil)
          (->Tab :files nil)
          (->Tab :repl-in nil)
          (->Tab :repl-out nil)
          (->Font (/ 1 4))
          (map->State {:buffer-updates []
                       :show-search? false
                       :mode 'NORMAL}))
        clara/fire-rules)))

(restart!)

