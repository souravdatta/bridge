(ns bridge.diff
  "Line-level diff engine + ANSI renderer.

  compute-diff  — Myers/LCS diff of two texts, returns a vector of
                  [kind line] tuples (:eq :add :del).
  render-diff   — Renders to an ANSI-coloured string suitable for terminal
                  output. Unchanged runs longer than 3 lines are folded to
                  keep the preview readable."
  (:require [clojure.string :as str]))


;; ---------------------------------------------------------------------------
;; ANSI colours
;; ---------------------------------------------------------------------------

(def ^:private reset "\033[0m")
(def ^:private red   "\033[31m")
(def ^:private green "\033[32m")
(def ^:private gray  "\033[90m")


;; ---------------------------------------------------------------------------
;; LCS-based line diff
;;
;; Uses a Java int[][] DP table for speed — avoids Clojure persistent-vector
;; overhead on the inner loop. Object arrays hold the line strings so
;; equality checks stay as fast .equals() calls.
;; ---------------------------------------------------------------------------

(defn- to-lines
  "Split TEXT into a vector of lines. Returns [] for nil or empty string."
  [text]
  (if (or (nil? text) (= "" text))
    []
    (str/split-lines text)))

(defn- lcs-table
  "Build the LCS length table for A-LINES and B-LINES.
  Returns a Java int[][] where t[i][j] = length of LCS of
  a-lines[0..i-1] and b-lines[0..j-1]."
  ^"[[I" [a-lines b-lines]
  (let [m         (count a-lines)
        n         (count b-lines)
        ^objects a (object-array a-lines)
        ^objects b (object-array b-lines)
        ^"[[I" t   (make-array Integer/TYPE (inc m) (inc n))]
    (dotimes [i m]
      (let [ii        (inc i)
            ^ints ti  (aget t i)
            ^ints tii (aget t ii)]
        (dotimes [j n]
          (let [jj (inc j)]
            (aset tii jj
                  (if (= (aget a i) (aget b j))
                    (inc (aget ti j))
                    (max (aget ti jj)
                         (aget tii j))))))))
    t))


(defn compute-diff
  "Compare OLD-TEXT and NEW-TEXT line by line using LCS.
  Returns a vector of [kind line] tuples where kind is :eq, :add, or :del.
  :eq — line present in both (unchanged)
  :add — line present only in NEW-TEXT (addition)
  :del — line present only in OLD-TEXT (deletion)"
  [old-text new-text]
  (let [old (to-lines old-text)
        nw  (to-lines new-text)]
    (if (and (empty? old) (empty? nw))
      []
      (let [^objects a (object-array old)
            ^objects b (object-array nw)
            ^"[[I" t   (lcs-table old nw)
            m          (count old)
            n          (count nw)]
        (loop [i m j n result '()]
          (cond
            (and (zero? i) (zero? j))
            (vec result)

            (zero? i)
            (recur i (dec j) (cons [:add (aget b (dec j))] result))

            (zero? j)
            (recur (dec i) j (cons [:del (aget a (dec i))] result))

            (= (aget a (dec i)) (aget b (dec j)))
            (recur (dec i) (dec j) (cons [:eq (aget a (dec i))] result))

            (>= (aget ^ints (aget t (dec i)) j)
                (aget ^ints (aget t i) (dec j)))
            (recur (dec i) j (cons [:del (aget a (dec i))] result))

            :else
            (recur i (dec j) (cons [:add (aget b (dec j))] result))))))))


;; ---------------------------------------------------------------------------
;; ANSI renderer
;; ---------------------------------------------------------------------------

(defn- group-runs
  "Group DIFF-VEC into runs of the same kind.
  Returns a vector of {:kind k :lines [line ...]} maps."
  [diff-vec]
  (reduce
    (fn [acc [kind line]]
      (if (and (seq acc) (= kind (:kind (peek acc))))
        (update acc (dec (count acc)) update :lines conj line)
        (conj acc {:kind kind :lines [line]})))
    []
    diff-vec))

(defn- render-eq-run
  "Render a run of unchanged lines. Folds the middle when count > 3."
  [lines]
  (if (<= (count lines) 3)
    (str/join "\n" (map #(str gray " " % reset) lines))
    (str
      (str gray " " (first lines) reset) "\n"
      (str gray "  ... " (- (count lines) 2) " unchanged lines ..." reset) "\n"
      (str gray " " (last lines) reset))))

(defn render-diff
  "Render DIFF-VEC as an ANSI-coloured unified-style diff string.
  FILE-PATH is shown in the header.
  Unchanged line runs longer than 3 are folded with an ellipsis."
  [diff-vec file-path]
  (let [runs        (group-runs diff-vec)
        has-changes (some #(#{:add :del} (:kind %)) runs)
        header      (str gray "--- " file-path reset "\n"
                         gray "+++ " file-path reset)]
    (if (not has-changes)
      (str header "\n" gray "(no changes)" reset)
      (str header "\n"
           (str/join "\n"
             (map (fn [{:keys [kind lines]}]
                    (case kind
                      :eq  (render-eq-run lines)
                      :add (str/join "\n" (map #(str green "+" % reset) lines))
                      :del (str/join "\n" (map #(str red   "-" % reset) lines))))
                  runs))))))
