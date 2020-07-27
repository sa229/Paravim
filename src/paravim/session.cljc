(ns paravim.session
  (:require [paravim.buffers :as buffers]
            [paravim.scroll :as scroll]
            [paravim.constants :as constants]
            [paravim.minimap :as minimap]
            [clara.rules :as clara]
            [clarax.rules :as clarax]
            [odoyle.rules :as o #?(:clj :refer :cljs :refer-macros) [ruleset]]
            [clojure.string :as str]
            [clojure.core.async :as async]
            #?(:clj  [clarax.macros-java :refer [->session]]
               :cljs [clarax.macros-js :refer-macros [->session]]))
  #?(:cljs (:require-macros [paravim.session :refer [merge-into-session]])))

(defonce *initial-session (atom nil))
(defonce *initial-osession (atom nil))
(defonce *session (atom {}))
(defonce *reload? (atom false))

(defrecord Game [total-time delta-time context])
(defrecord Window [width height])
(defrecord TextBox [id left right top bottom])
(defrecord Font [size])
(defrecord FontMultiplier [size])
(defrecord CurrentTab [id])
(defrecord Tab [id buffer-id])
(defrecord Buffer [id tab-id
                   text-entity parinfer-text-entity rects-entity
                   parsed-code needs-parinfer? needs-parinfer-init? needs-clojure-refresh?
                   camera camera-x camera-y camera-target-x camera-target-y total-time-anchor
                   scroll-speed-x scroll-speed-y
                   path file-name
                   lines clojure?
                   cursor-line cursor-column
                   font-anchor window-anchor show-minimap?
                   last-update])
(defrecord Minimap [buffer-id show? text-entity rects-entity anchor])

(defn get-constants [session]
  (first (o/query-all session ::get-constants)))

(defn get-vim [session]
  (first (o/query-all session ::get-vim)))

(defn get-font [session]
  (first (o/query-all session ::get-font)))

(defn get-window [session]
  (first (o/query-all session ::get-window)))

(defn get-current-tab [session]
  (first (o/query-all session ::get-current-tab)))

(defn get-tab [session tab-id]
  (some (fn [tab]
          (when (= tab-id (:id tab))
            tab))
        (o/query-all session ::get-tab)))

(defn get-mouse [session]
  (first (o/query-all session ::get-mouse)))

(defn get-text-box [session tab-id]
  (some (fn [text-box]
          (when (= tab-id (:id text-box))
            text-box))
        (o/query-all session ::get-text-box)))

(defn get-bounding-box [session box-id]
  (some (fn [bounding-box]
          (when (= box-id (:id bounding-box))
            bounding-box))
        (o/query-all session ::get-bounding-box)))

(defn get-buffer' [session buffer-id]
  (some (fn [buffer]
          (when (= buffer-id (:id buffer))
            buffer))
        (o/query-all session ::get-buffer)))

(defn get-buffer [{:keys [session osession]} buffer-id]
  #_(clara/query session ::get-buffer :?id buffer-id)
  (get-buffer' osession buffer-id))

(defn get-current-buffer [session]
  (first (o/query-all session ::get-current-buffer)))

(defn get-minimap [session buffer-id]
  (some (fn [minimap]
          (when (= buffer-id (:id minimap))
            minimap))
        (o/query-all session ::get-minimap)))

(def oqueries
  (o/ruleset
    {::get-constants
     [:what
      [::constant ::base-rect-entity base-rect-entity]
      [::constant ::base-rects-entity base-rects-entity]
      [::constant ::font-width font-width]
      [::constant ::font-height font-height]
      [::constant ::base-font-entity base-font-entity]
      [::constant ::base-text-entity base-text-entity]
      [::constant ::roboto-font-entity roboto-font-entity]
      [::constant ::roboto-text-entity roboto-text-entity]
      [::constant ::toolbar-text-entities toolbar-text-entities]
      [::constant ::highlight-text-entities highlight-text-entities]]

     ::get-vim
     [:what
      [::vim ::mode mode]
      [::vim ::ascii ascii]
      [::vim ::control? control?]
      [::vim ::show-search? show-search?]
      [::vim ::visual-range visual-range]
      [::vim ::highlights highlights]
      [::vim ::message message]
      [::vim ::command-start command-start]
      [::vim ::command-text command-text]
      [::vim ::command-completion command-completion]
      [::vim ::command-text-entity command-text-entity]
      [::vim ::command-cursor-entity command-cursor-entity]]

     ::get-font
     [:what
      [::font ::size size]
      [::font ::multiplier multiplier]]

     ::get-window
     [:what
      [::window ::width width]
      [::window ::height height]]

     ::get-current-tab
     [:what
      [::tab ::current id]]

     ::get-tab
     [:what
      [id ::buffer-id buffer-id]]

     ::get-mouse
     [:what
      [::mouse ::x x]
      [::mouse ::y y]
      [::mouse ::target target]
      [::mouse ::cursor cursor]]

     ::get-text-box
     [:what
      [id ::left left]
      [id ::right right]
      [id ::top top]
      [id ::bottom bottom]]

     ::get-bounding-box
     [:what
      [id ::x1 x1]
      [id ::y1 y1]
      [id ::x2 x2]
      [id ::y2 y2]
      [id ::align align]]

     ::get-buffer
     [:what
      [id ::tab-id tab-id]
      [id ::text-entity text-entity]
      [id ::parinfer-text-entity parinfer-text-entity]
      [id ::rects-entity rects-entity]
      [id ::parsed-code parsed-code]
      [id ::needs-parinfer? needs-parinfer?]
      [id ::needs-parinfer-init? needs-parinfer-init?]
      [id ::needs-clojure-refresh? needs-clojure-refresh?]
      [id ::camera camera]
      [id ::camera-x camera-x]
      [id ::camera-y camera-y]
      [id ::camera-target-x camera-target-x]
      [id ::camera-target-y camera-target-y]
      [id ::scroll-speed-x scroll-speed-x]
      [id ::scroll-speed-y scroll-speed-y]
      [id ::path path]
      [id ::file-name file-name]
      [id ::lines lines]
      [id ::clojure? clojure]
      [id ::cursor-line cursor-line]
      [id ::cursor-column cursor-column]
      [id ::show-minimap? show-minimap?]]

     ::get-current-buffer
     [:what
      [::tab ::current tab-id]
      [tab-id ::buffer-id buffer-id]]

     ::get-minimap
     [:what
      [id ::minimap/show? show?]
      [id ::minimap/rects-entity rects-entity]
      [id ::minimap/text-entity text-entity]]}))

(def orules
  (o/ruleset
    {::init-font-size
     [:what
      [::constant ::font-height font-height]
      [::font ::size size {:then false}]
      [::font ::multiplier multiplier]
      :when
      (== 0 size)
      :then
      (o/insert! ::font ::size (* multiplier font-height))]

     ::update-font-multiplier
     [:what
      [::constant ::font-height font-height]
      [::font ::size size]
      [::font ::multiplier multiplier {:then false}]
      :when
      (pos? size)
      :then
      (o/insert! ::font ::multiplier (/ size font-height))]

     ::mouse-hovers-over-text
     [:what
      [::window ::width window-width]
      [::window ::height window-height]
      [::mouse ::x mouse-x]
      [::mouse ::y mouse-y]
      [::tab ::current tab-id]
      [::font ::multiplier font-multiplier]
      [tab-id ::left left]
      [tab-id ::right right]
      [tab-id ::top top]
      [tab-id ::bottom bottom]
      :when
      (<= left mouse-x (- window-width right))
      (<= (top window-height font-multiplier)
          mouse-y
          (bottom window-height font-multiplier))
      :then
      (o/insert! ::mouse {::target :text
                          ::cursor :ibeam})]

     ::mouse-hovers-over-bounding-box
     [:what
      [::window ::width window-width]
      [::mouse ::x mouse-x]
      [::mouse ::y mouse-y]
      [::font ::multiplier font-multiplier]
      [id ::x1 x1]
      [id ::y1 y1]
      [id ::x2 x2]
      [id ::y2 y2]
      [id ::align align]
      :when
      (let [x1 (cond->> (* x1 font-multiplier)
                        (= :right align)
                        (- window-width))
            y1 (* y1 font-multiplier)
            x2 (cond->> (* x2 font-multiplier)
                        (= :right align)
                        (- window-width))
            y2 (* y2 font-multiplier)]
        (and (<= x1 mouse-x x2)
             (<= y1 mouse-y y2)))
      :then
      (o/insert! ::mouse {::target id
                          ::cursor :hand})]

     ::update-buffer-when-font-changes-or-window-resizes
     [:what
      [::global ::single-command-chan single-command-chan]
      [::vim ::mode mode {:then false}]
      [::vim ::visual-range visual-range {:then false}]
      [::vim ::show-search? show-search? {:then false}]
      [::vim ::highlights highlights {:then false}]
      [::font ::multiplier multiplier]
      [::window ::width window-width]
      [::window ::height window-height]
      [id ::tab-id tab-id {:then false}]
      :when
      ;; ignore ascii buffers
      (number? id)
      :then
      (let [constants (get-constants o/*session*)
            buffer (get-buffer' o/*session* id)
            text-box (get-text-box o/*session* tab-id)
            window {:width window-width :height window-height}
            new-buffer (-> buffer
                          (paravim.buffers/update-cursor mode multiplier text-box constants window)
                          (paravim.buffers/update-highlight constants)
                          (paravim.buffers/update-selection constants visual-range)
                          (paravim.buffers/update-search-highlights constants show-search? highlights))]
        (doseq [[k v] new-buffer
                :when (not= v (get buffer k))]
          ;; FIXME: temporary hack
          (o/insert! id (keyword "paravim.session" (name k)) v))
        (async/put! single-command-chan [:resize-window]))]

     ::move-camera-to-target
     [:what
      [::time ::delta delta-time]
      [id ::camera-x camera-x {:then false}]
      [id ::camera-y camera-y {:then false}]
      [id ::camera-target-x camera-target-x {:then false}]
      [id ::camera-target-y camera-target-y {:then false}]
      [id ::scroll-speed-x scroll-speed-x {:then false}]
      [id ::scroll-speed-y scroll-speed-y {:then false}]
      :when
      (or (not (== camera-x camera-target-x))
          (not (== camera-y camera-target-y)))
      :then
      (let [new-buffer (scroll/animate-camera
                         camera-x camera-y
                         camera-target-x camera-target-y
                         scroll-speed-x scroll-speed-y
                         delta-time)]
        (doseq [[k v] new-buffer]
          ;; FIXME: temporary hack
          (o/insert! id (keyword "paravim.session" (name k)) v)))]

     ::rubber-band-effect
     [:what
      [::font ::multiplier multiplier]
      [::window ::width window-width]
      [::window ::height window-height]
      [id ::tab-id tab-id]
      [id ::text-entity text-entity]
      [id ::show-minimap? show-minimap?]
      [id ::camera-target-x camera-target-x]
      [id ::camera-target-y camera-target-y]
      [id ::scroll-speed-x scroll-speed-x]
      [id ::scroll-speed-y scroll-speed-y]
      :then
      (let [constants (get-constants o/*session*)
            text-box (get-text-box o/*session* tab-id)
            window {:width window-width :height window-height}
            new-buffer (scroll/rubber-band-camera
                         text-entity show-minimap?
                         camera-target-x camera-target-y
                         scroll-speed-x scroll-speed-y
                         multiplier text-box constants window)]
        (doseq [[k v] new-buffer]
          ;; FIXME: temporary hack
          (o/insert! id (keyword "paravim.session" (name k)) v)))]

     ::minimap
     [:what
      [::time ::delta delta-time]
      [::font ::multiplier multiplier]
      [::window ::width window-width]
      [::window ::height window-height]
      [id ::tab-id tab-id]
      [id ::text-entity text-entity]
      [id ::lines lines]
      [id ::camera-x camera-x {:then false}]
      [id ::camera-y camera-y {:then false}]
      [id ::show-minimap? show-minimap? {:then false}]
      :when
      ;; ignore ascii buffers
      (number? id)
      :then
      (let [text-box (get-text-box o/*session* tab-id)
            constants (get-constants o/*session*)
            new-minimap (paravim.minimap/->minimap
                          text-entity lines camera-x camera-y
                          constants multiplier
                          window-width window-height text-box)
            show? (::minimap/show? new-minimap)]
        (o/insert! id new-minimap)
        (when (not= show? show-minimap?)
          (o/insert! id ::show-minimap? show?)))]}))

(def rules
  '{})

(def queries
  '{::get-game
    (fn []
      (let [game paravim.session.Game]
        game))
    ::get-window
    (fn []
      (let [window paravim.session.Window]
        window))
    ::get-current-tab
    (fn []
      (let [current-tab paravim.session.CurrentTab]
        current-tab))
    ::get-tab
    (fn [?id]
      (let [tab paravim.session.Tab
            :when (= (:id tab) ?id)]
        tab))
    ::get-font
    (fn []
      (let [font paravim.session.Font]
        font))
    ::get-font-multiplier
    (fn []
      (let [font paravim.session.FontMultiplier]
        font))
    ::get-text-box
    (fn [?id]
      (let [text-box paravim.session.TextBox
            :when (= (:id text-box) ?id)]
        text-box))
    ::get-buffer
    (fn [?id]
      (let [buffer paravim.session.Buffer
            :when (= (:id buffer) ?id)]
        buffer))
    ::get-minimap
    (fn [?id]
      (let [minimap paravim.session.Minimap
            :when (= (:buffer-id minimap) ?id)]
        minimap))})

#?(:clj (defmacro merge-into-session [& args]
          `(do
             (reset! *initial-session (->session ~(->> (apply merge queries rules args)
                                                       ;; remove nil rules (this allows people to disable rules)
                                                       (filter second)
                                                       (into {}))))

             (reset! *initial-osession
               (reduce o/add-rule (o/->session) (concat oqueries orules)))
             ;; reload the session if it's been created already
             (when @*session
               (reset! *reload? true))
             nil)))

;; create initial session
(merge-into-session)

(defn def-queries [{:keys [session]}]
  (let [query-fns (clarax/query-fns session)]
    (def get-game (::get-game query-fns))
    (def get-font-multiplier (::get-font-multiplier query-fns))))

