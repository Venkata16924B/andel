(ns andel.parens
  (:require [andel.text :as text]
            [andel.core :as core]
            [andel.cursor :as cursor]
            [andel.controller :as controller]
            [andel.intervals :as intervals])
  #?(:clj (:import [andel.cursor Cursor TransientCursor])))

(def closing? #{\) \} \]})

(def opening? #{\( \{ \[})

(def paren? (clojure.set/union closing? opening?))

(def opposite {\( \) \) \( \[ \] \] \[ \{ \} \} \{})

(defn paren-symbol? [c] (or (closing? c) (opening? c)))

(defn paren-token? [text lexer]
  (fn [offset]
    (if (some? lexer)
      (intervals/is-brace-token? lexer offset)
      (paren? (-> text text/text->char-seq (nth offset))))))

(defn- find-matching-paren [text paren-token? offset should-push? should-pop? advance]
  (when (and (<= 0 offset)
             (< offset (text/text-length text)))
    (let [t-cursor ^TransientCursor (cursor/transient (cursor/make-cursor text offset))
          paren  (.getChar t-cursor)]
      (when (and (paren-symbol? paren)
                 (paren-token? offset))
        (loop [s '()]
          (advance t-cursor)
          (let [c (.getChar t-cursor)
                o (.getOffset t-cursor)]
            (cond
              (.isExhausted t-cursor) nil
              (not (paren-token? o)) (recur s)
              (should-push? c) (recur (cons c s))
              (should-pop? c) (cond (= c (opposite (first s))) (recur (rest s))
                                    (= c (opposite paren))     o
                                    :else (recur s))
              :else (recur s))))))))

(defn find-matching-paren-forward [text paren-token? offset]
  (when-let [to (find-matching-paren text paren-token? offset opening? closing? #(.next! ^TransientCursor %))]
    [offset to]))

(defn find-matching-paren-backward [text paren-token? offset]
  (when-let [from (find-matching-paren text paren-token? offset closing? opening? #(.prev! ^TransientCursor %))]
    [from offset]))

(defn find-unbalanced-paren [text paren-token? offset should-push? should-pop? advance]
  (when (and (<= 0 offset)
             (< offset (text/text-length text)))
    (let [t-cursor ^TransientCursor (cursor/transient (cursor/make-cursor text offset))]
      (loop [s '()]
        (advance t-cursor)
        (when (not (.isExhausted t-cursor))
          (let [c (.getChar t-cursor)
                o (.getOffset t-cursor)]
            (cond
              (not (paren-token? o)) (recur s)
              (should-push? c) (recur (cons c s))
              (should-pop? c) (cond (= c (opposite (first s))) (recur (rest s))
                                    :else o)
              :else (recur s))))))))

(defn find-closing-paren [text paren-token? offset]
  (find-unbalanced-paren text paren-token? offset opening? closing? #(.next! ^TransientCursor %)))

(defn find-opening-paren [text paren-token? offset]
  (find-unbalanced-paren text paren-token? offset closing? opening? #(.prev! ^TransientCursor %)))

(defn find-parens-pair [text paren-token? offset]
  (let [len (text/text-length text)]
    (when (< 0 len)
      (let [prev-offset (max (dec offset) 0)
            offset (min offset (dec len))
            c0 (-> text (cursor/make-cursor prev-offset) (cursor/get-char))
            c1 (-> text (cursor/make-cursor offset) (cursor/get-char))]
        (cond
          (closing? c0) (find-matching-paren-backward text paren-token? prev-offset)
          (opening? c1) (find-matching-paren-forward text paren-token? offset)
          :else         nil)))))

(defonce unique-paren-id
  (let [a (atom 0)]
    #(swap! a inc)))

(defn highlight-parens [{:keys [editor document] :as state}]
  (let [caret-offset  (core/caret-offset state)
        lexer (:lexer document)
        text (:text document)
        paren-token? (paren-token? text lexer)
        [p-from p-to] (find-parens-pair text
                                        paren-token?
                                        caret-offset)
        old-paren-ids (:paren-ids editor)
        state (core/delete-markers state old-paren-ids)]
    (if (and p-from p-to)
      (let [from-id (unique-paren-id)
            to-id   (unique-paren-id)]
        (-> state
            (core/insert-markers [(intervals/->Marker p-from
                                                      (inc p-from)
                                                      false
                                                      false
                                                      (intervals/->Attrs from-id "highlight-paren" "" :background))
                                  (intervals/->Marker p-to
                                                      (inc p-to)
                                                      false
                                                      false
                                                      (intervals/->Attrs to-id "highlight-paren" "" :background))])
            (assoc-in [:editor :paren-ids] [from-id to-id])))
      state)))


(defn enclosing-parens [text paren-token? offset]
  (let [opening (find-opening-paren text paren-token? offset)
        closing (find-closing-paren text paren-token? (dec offset))]
    (when (some? (and opening closing))
      [opening closing])))

(def whitespace? #{\newline \space \tab})

(defn not-whitespace-or-paren? [c]
  (and (not (whitespace? c))
       (not (paren? c))))

(defn find-next-form [text paren-token? offset]
  (when (< offset (text/text-length text))
    (let [cursor (cursor/make-cursor text offset)
          form-start-cursor (first (cursor/move-while cursor whitespace? :forward))
          form-start-offset (+ offset (cursor/distance cursor form-start-cursor))
          form-start-char   (cursor/get-char form-start-cursor)]
      (cond (paren-symbol? form-start-char) (find-matching-paren-forward text paren-token? form-start-offset)
            (= \" form-start-char) [form-start-char (+ form-start-offset 1 (cursor/count-matching (cursor/next form-start-cursor) #(not= \" %) :forward))]
            (= \; form-start-char) [form-start-char (+ form-start-offset 1 (cursor/count-matching (cursor/next form-start-cursor) #(not= \newline %) :forward))]
            :else (let [[form-end-cursor end?] (cursor/move-while form-start-cursor not-whitespace-or-paren? :forward)
                        form-end-offset (if end?
                                          (cursor/offset form-end-cursor)
                                          (dec (cursor/offset form-end-cursor)))]
                    [form-start-offset form-end-offset])))))

(defn find-prev-form [text paren-token? offset]
  (when (<= 0 offset)
    (let [cursor          (cursor/make-cursor text offset)
          form-end-cursor (first (cursor/move-while cursor whitespace? :backward))
          form-end-offset (- offset (cursor/distance cursor form-end-cursor))
          form-end-char   (cursor/get-char form-end-cursor)]
      (cond (paren-symbol? form-end-char) (find-matching-paren-backward text paren-token? form-end-offset)
            (= \" form-end-char) [(- form-end-offset 1 (cursor/count-matching (cursor/prev form-end-cursor) #(not= \" %) :backward)) form-end-offset]
            :else (let [[form-start-cursor end?] (cursor/move-while form-end-cursor not-whitespace-or-paren? :backward)
                        form-start-offset (if end?
                                            (cursor/offset form-start-cursor)
                                            (inc (cursor/offset form-start-cursor)))]
                    [form-start-offset form-end-offset])))))
