(ns bridge.rules)

(defn match-var
  [v]
  (cond (and (symbol? v)
             (= (subs (str v) 0 1) "?"))
        (keyword (subs (str v) 1))
        (and (keyword? v)
             (= (subs (str v) 1 2) "?"))
        (keyword (subs (str v) 2))))


(defn match-star-var
  [v]
  (cond (and (symbol? v)
             (= (subs (str v) 0 1) "*"))
        (keyword (subs (str v) 1))
        (and (keyword? v)
             (= (subs (str v) 1 2) "*"))
        (keyword (subs (str v) 2))))

(defn find-while
  [things thing in-things]
  (cond
    (match-star-var thing) [in-things things]
    (match-var thing) [(concat in-things (butlast things)) [(last things)]]
    (not (seq things)) (if (nil? thing)
                         [in-things []]
                         nil)
    (= (first things) thing) [in-things things]
    :else (recur (rest things) thing (conj in-things (first things)))))


(defn match
  [m1 m2 bindings]
  (cond
    (nil? bindings) nil
    (or (not (seq m1))
        (not (seq m2))) bindings
    (match-var (first m1)) (recur
                            (rest m1)
                            (rest m2)
                            (conj bindings [(match-var (first m1)) (first m2)]))
    (match-star-var (first m1)) (let [extracted-vals (find-while
                                                      m2
                                                      (if (not (seq (rest m1)))
                                                        nil
                                                        (first (rest m1)))
                                                      [])]
                                  (if (nil? extracted-vals)
                                    nil
                                    (recur
                                     (rest m1)
                                     (extracted-vals 1)
                                     (conj bindings [(match-star-var
                                                      (first m1))
                                                     (extracted-vals 0)]))))
    (= (first m1) (first m2)) (recur (rest m1) (rest m2) bindings)
    :else nil))


(defn match-s
  [m1 m2]
  (match m1 (map (fn [s] (.toLowerCase s)) (clojure.string/split m2 #"\W+")) {}))


(defmacro rule
  [& parts]
  `(map (fn [e#] (if (or (match-var e#) (match-star-var e#)) e# (str e#))) '~parts))


(defn make-rule
  [rs action]
  (fn [s]
    (let [m (some #(match-s % s) rs)]
      (if m
        (action m)
        nil))))


(defn make-rules
  [rule-defs]
  (map #(make-rule (first %) (second %)) rule-defs))


(defn match-all-rules
  "Try each rule in turn against input string s. Returns the first match result, or nil."
  [rules s]
  (some #(% s) rules))


(comment
  ;; Example rules — kept here as reference / REPL scratch.
  ;; Load this block in the REPL to experiment with the engine.

  (def notes (make-rule [(rule :*pre note that :*note)
                         (rule :*pre note :*note)]
                        (fn [m] {:data    (m :note)
                                 :context :notes
                                 :reply   "Ok, I have noted that!"
                                 :more    {:note (clojure.string/join " " (m :note))}})))

  (def greetings (make-rule [(rule hello :*rest)
                             (rule :* hi)
                             (rule :*p hello :*rest)
                             (rule :*a morning :*rest)
                             (rule hi :*rest)]
                            (fn [m] (if (and (m :rest)
                                             (notes (clojure.string/join " " (m :rest))))
                                      (notes (clojure.string/join " " (m :rest)))
                                      {:reply   "Hello!"
                                       :context :greetings
                                       :data    (if (m :rest) (m :rest) "greetings")}))))

  (def all-rules [greetings notes])

  (match-all-rules all-rules "hello note that the sky is blue")
  (match-all-rules all-rules "hi there")
  (match-all-rules all-rules "note that Clojure is excellent"))

