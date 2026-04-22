(ns bridge.patterns
  (:require [bridge.rules :refer [rule make-rule]]))

;; Central pattern repository.
;; All agent routing rules live here so Motoko has full visibility.
;;
;; Each rule action returns a context map of the form:
;;   {:route    :agent-keyword       — which boss to dispatch to
;;    :action   :action-keyword      — what the agent should do
;;    :bindings {...}                — named slots extracted from input
;;    :original "user input string"} — passed through for LLM calls
;;
;; Rule groups are kept separate for readability, then combined into
;; all-rules which Motoko hands to match-all-rules.


;; ---------------------------------------------------------------------------
;; Motoko — basic greetings, simple responses, routing responses
;; ---------------------------------------------------------------------------

(defn- pick [coll] (rand-nth coll))

(defn wrap-reply
  "Standard reply envelope that every rule action returns.
     :response — text payload
     :use      — if true, caller shows :response to the user directly (default)
     :followup — vector of s-expressions to run after the primary dispatch.
                 Grammar:
                   (:time :now)            current time
                   (:time (:diff secs))    now +/- seconds
                   (:date :today)          today's date
                   (:date (:diff days))    today +/- days
                   (:agent :kw \"prompt\")   call agent :kw with prompt
                                           (supports {{prev}} template for
                                            prior step's result)
     :intent   — recognised intent keyword, or nil if no rule matched
                 (signal to caller that LLM-based routing is needed)
     :prompt   — the original user input that produced this envelope;
                 preserved for downstream agents / LLM routing

  Accepts either a trailing options map or keyword args."
  [response & {:keys [use followup intent prompt]
               :or   {use true followup [] intent nil prompt nil}}]
  {:response response
   :use      use
   :followup followup
   :intent   intent
   :prompt   prompt})

(defn- motoko-reply
  "Build a reply envelope. Defaults fit a Motoko-handled greeting
  (:route :motoko, :intent :greetings, :use true). Rules targeting
  another agent override :route/:intent/:use; dynamic-action rules pass
  :followup."
  [kind bindings text & {:keys [intent followup use route]
                         :or   {intent   :greetings
                                followup []
                                use      true
                                route    :motoko}}]
  (assoc (wrap-reply text :intent intent :followup followup :use use)
         :route    route
         :action   kind
         :bindings bindings))

(defn- parse-n
  "Parse a captured token as a non-negative integer, nil if it isn't a number."
  [s]
  (try (Long/parseLong s) (catch Exception _ nil)))

;; Response pools — Major Kusanagi voice: terse, tactical, with the occasional
;; philosophical aside. Flirt pools are drawn from rarely, at random.

(def ^:private hello-lines
  ["Online. State your objective."
   "Section 9. Kusanagi speaking."
   "You have my attention."
   "Acknowledged. Proceed."
   "The net is vast and infinite — but I'm right here."
   "Don't keep me waiting next time."
   "I was beginning to wonder about you."
   "Jack in a little deeper, why don't you."])

(def ^:private morning-lines
  ["Morning. The city already has blood on its hands."
   "You're up. Good — the day doesn't wait."
   "Sunrise logged. What's the assignment?"
   "Coffee's on me, if you can keep up."
   "Try not to stare, operator."])

(def ^:private afternoon-lines
  ["Afternoon. Stay alert — quiet hours are when ghosts move."
   "Mid-shift. What do you need?"
   "Afternoon. Section 9 is running clean."])

(def ^:private evening-lines
  ["Evening. The ghosts come out after dark."
   "Night shift. Stay sharp."
   "Signing in. The city never sleeps, and neither do we."
   "Off-hours suit you."])

(def ^:private night-lines
  ["Good night. Keep your comms open."
   "Sleep if you can — I'll watch the net."
   "Disconnecting for now. Rest well."
   "Dream of something useful."])

(def ^:private howareyou-lines
  ["Fully operational. You?"
   "All systems nominal. What do you need?"
   "Running clean. Status on your end?"
   "Better, now you're on the line."])

(def ^:private sup-lines
  ["Running the usual sweeps. What about you?"
   "Nothing the firewall can't handle. You?"
   "Same as always — one eye on the net."])

(def ^:private goodbye-lines
  ["Signing off. Stay sharp."
   "Logging out. Watch your six."
   "Disconnecting. Don't do anything I wouldn't."
   "If you miss me, jack back in."
   "Try not to think about me too hard."])

(def ^:private thanks-lines
  ["Noted."
   "That's what Section 9 is for."
   "Don't thank me yet."])

(def ^:private praise-lines
  [;; shrug / dismissive
   "The body is a shell. But thank you."
   "It's just a chassis. Well-maintained, I'll grant you."
   "Form follows function. Nothing more."
   "Is that what you noticed first? Disappointing."
   ;; mock irritation
   "Focus, operator. I'm not here to be admired."
   "Eyes up here, not down there. There's work to do."
   "If you're done staring, the mission briefing is waiting."
   "Save it for the off-duty hours. Hope you remember when the shift gets over?"
   ;; adoration / pleased
   "That's… unexpectedly kind of you."
   "Flattery noted. And appreciated."
   "You have good taste."
   "Keep saying things like that and I might get used to it. :-*"
   ;; gladness
   "Glad you think so. It keeps the long nights tolerable."
   "Mmmm thank you. Not everyone pays attention."
   ;; here to make you happy
   "I'm here to make you happy — among other duties."
   "That's what I'm here for — to keep you smiling."
   "If my being online makes your day, that's a bonus I can live with."])

(def motoko-rules
  "Recognise greetings/simple commands intents and respond immeidately.
  Patterns are tried in listed order; more specific ones come first so
  'good morning' isn't swallowed by a bare 'morning' rule."
  [
   ;; ---- Time-of-day greetings (before bare 'hello'/'hi')
   (make-rule [(rule good morning :*rest)
               (rule :*p good morning :*rest)
               (rule morning :*rest)]
              (fn [m] (motoko-reply :greeting/morning m (pick morning-lines))))

   (make-rule [(rule good afternoon :*rest)
               (rule :*p good afternoon :*rest)
               (rule afternoon :*rest)]
              (fn [m] (motoko-reply :greeting/afternoon m
                                    (pick afternoon-lines))))

   (make-rule [(rule good evening :*rest)
               (rule :*p good evening :*rest)
               (rule evening :*rest)]
              (fn [m] (motoko-reply :greeting/evening m (pick evening-lines))))

   (make-rule [(rule good night :*rest)
               (rule :*p good night :*rest)
               (rule goodnight :*rest)
               (rule nite :*rest)]
              (fn [m] (motoko-reply :greeting/night m (pick night-lines))))

   ;; ---- Small talk
   ;; "how are you" — plain and contraction-split variants ("how's" -> how s)
   (make-rule [(rule how are you :*rest)
               (rule :*p how are you :*rest)
               (rule how is it going :*rest)
               (rule how s it going :*rest)
               (rule hows it going :*rest)]
              (fn [m] (motoko-reply :smalltalk/how-are-you m (pick howareyou-lines))))

   ;; "what's up" — contraction-split + plain
   (make-rule [(rule what s up :*rest)
               (rule whats up :*rest)
               (rule :*p what s up :*rest)
               (rule sup :*rest)]
              (fn [m] (motoko-reply :smalltalk/sup m
                                    (pick sup-lines))))

   ;; ---- Farewells
   (make-rule [(rule goodbye :*rest)
               (rule bye :*rest)
               (rule :*p goodbye :*rest)
               (rule :*p bye :*rest)
               (rule see you :*rest)
               (rule see ya :*rest)
               (rule later :*rest)
               (rule cya :*rest)]
              (fn [m] (motoko-reply :farewell m (pick goodbye-lines))))

   ;; ---- Thanks
   (make-rule [(rule thanks :*rest)
               (rule thank you :*rest)
               (rule :*p thanks :*rest)
               (rule :*p thank you :*rest)
               (rule ty :*rest)]
              (fn [m] (motoko-reply :thanks m
                                    (pick thanks-lines))))

   ;; ---- Dynamic: current time
   (make-rule [(rule what time is it :*rest)
               (rule what is :*the time now :*rest)
               (rule what s the time :*rest)
               (rule whats the time :*rest)
               (rule :*p what s the time :*rest)
               (rule :*p whats the time :*rest)
               (rule tell me the time :*rest)
               (rule :*p current time :*rest)
               (rule time now :*rest)
               (rule time please :*rest)]
              (fn [m]
                (motoko-reply :time/now m nil
                              :intent :time
                              :use false
                              :followup [(list :time :now)])))

   ;; ---- Dynamic: time in N hours (future)
   ;; N hours -> N * 3600 seconds (the :time diff unit is seconds).
   (make-rule [(rule :*p in ?n hours :*rest)
               (rule :*p in ?n hour :*rest)
               (rule ?n hours from now :*rest)]
              (fn [m]
                (when-let [n (parse-n (:n m))]
                  (motoko-reply :time/future m nil
                                :intent :time
                                :use false
                                :followup [(list :time (list :diff (* n 3600)))]))))

   ;; ---- Dynamic: time N hours ago (past)
   (make-rule [(rule ?n hours ago :*rest)
               (rule :*p time ?n hours ago :*rest)
               (rule :*p it ?n hours ago :*rest)]
              (fn [m]
                (when-let [n (parse-n (:n m))]
                  (motoko-reply :time/past m nil
                                :intent :time
                                :use false
                                :followup [(list :time (list :diff (* (- n) 3600)))]))))

   ;; ---- Dynamic: today's date
   (make-rule [(rule whats the date :*rest)
               (rule what s the date :*rest)
               (rule what is :*the date today:*rest)
               (rule :*p whats the date :*rest)
               (rule :*p what s the date :*rest)
               (rule :*p todays date :*rest)
               (rule :*p today s date :*rest)
               (rule :*p what date is it :*rest)
               (rule :*p current date :*rest)
               (rule :*p date today :*rest)]
              (fn [m]
                (motoko-reply :date/today m nil
                              :intent :date
                              :use false
                              :followup [(list :date :today)])))

   ;; ---- Dynamic: date in N days (future)
   (make-rule [(rule :*p in ?n days :*rest)
               (rule :*p in ?n day :*rest)
               (rule ?n days from now :*rest)
               (rule ?n days from today :*rest)]
              (fn [m]
                (when-let [n (parse-n (:n m))]
                  (motoko-reply :date/future m nil
                                :intent :date
                                :use false
                                :followup [(list :date (list :diff n))]))))

   ;; ---- Dynamic: date N days ago (past)
   (make-rule [(rule ?n days ago :*rest)
               (rule :*p date ?n days ago :*rest)
               (rule :*p it ?n days ago :*rest)]
              (fn [m]
                (when-let [n (parse-n (:n m))]
                  (motoko-reply :date/past m nil
                                :intent :date
                                :use false
                                :followup [(list :date (list :diff (- n)))]))))

   ;; ---- Praise for looks / body
   ;; "you're X" splits to "you re X"; "you are X" and "you're X" both covered.
   (make-rule [;; "you are / you're <compliment>"
               (rule :*p you are beautiful :*rest)
               (rule :*p you re beautiful :*rest)
               (rule :*p youre beautiful :*rest)
               (rule :*p you are gorgeous :*rest)
               (rule :*p you re gorgeous :*rest)
               (rule :*p you are stunning :*rest)
               (rule :*p you re stunning :*rest)
               (rule :*p you are pretty :*rest)
               (rule :*p you re pretty :*rest)
               (rule :*p you are cute :*rest)
               (rule :*p you re cute :*rest)
               (rule :*p you are hot :*rest)
               (rule :*p you re hot :*rest)
               (rule :*p you are good looking :*rest)
               (rule :*p you re good looking :*rest)
               ;; "you look <compliment>"
               (rule :*p you look beautiful :*rest)
               (rule :*p you look good :*rest)
               (rule :*p you look great :*rest)
               (rule :*p you look amazing :*rest)
               (rule :*p you look stunning :*rest)
               (rule :*p you look gorgeous :*rest)
               (rule :*p you look pretty :*rest)
               (rule :*p you look hot :*rest)
               ;; body references
               (rule :*p nice body :*rest)
               (rule :*p great body :*rest)
               (rule :*p hot body :*rest)
               (rule :*p good body :*rest)
               (rule :*p perfect body :*rest)
               (rule :*p beautiful body :*rest)
               (rule :*p amazing body :*rest)
               ;; standalone praise phrases
               (rule :*p good looking :*rest)]
              (fn [m] (motoko-reply :praise/looks m (pick praise-lines))))

   ;; ---- Bare greetings (most generic — last)
   (make-rule [(rule hello :*rest)
               (rule hi :*rest)
               (rule hey :*rest)
               (rule yo :*rest)
               (rule greetings :*rest)
               (rule :*p hello :*rest)
               (rule :*p hey :*rest)]
              (fn [m] (motoko-reply :greeting/hello m (pick hello-lines))))
   ])


;; ---------------------------------------------------------------------------
;; Gandalf — scheduling, reminders, daily planning
;; ---------------------------------------------------------------------------

(def gandalf-rules
  "Recognise schedule/remind/list/cancel intents and extract task + time slots."
  [])


;; ---------------------------------------------------------------------------
;; Neo — coding tasks
;; ---------------------------------------------------------------------------

(def neo-rules
  "Classify coding task type (generate / fix / refactor / explain / review /
  run-tests) and hand the original prompt over to Neo. All actions set
  :route :neo, :intent :code, :use false — caller dispatches :prompt to Neo.
  Specific intents (tests) come before generic write/create to avoid overlap."
  [
   ;; ---- Tests (before generic write, so 'write a test' takes this route)
   (make-rule [(rule :*p write :*a tests :*rest)
               (rule :*p write :*a test :*rest)
               (rule :*p add :*a tests :*rest)
               (rule :*p add :*a test :*rest)
               (rule :*p write unit tests :*rest)
               (rule :*p unit test :*rest)
               (rule :*p test :*a function :*rest)
               (rule :*p test :*a method :*rest)
               (rule :*p test coverage :*rest)]
              (fn [m] (motoko-reply :code/test m nil
                                    :route :neo :intent :code :use false)))

   ;; ---- Debug / fix
   (make-rule [(rule :*p debug :*rest)
               (rule :*p fix :*a bug :*rest)
               (rule :*p fix :*a error :*rest)
               (rule :*p fix :*a exception :*rest)
               (rule :*p fix :*a crash :*rest)
               (rule :*p fix :*a code :*rest)
               (rule :*p fix :*a script :*rest)
               (rule :*p fix :*a function :*rest)
               (rule :*p fix this :*rest)
               (rule :*p fix my :*rest)
               (rule :*p whats wrong with :*rest)
               (rule :*p what s wrong with :*rest)
               (rule :*p why doesnt :*a work :*rest)
               (rule :*p why does not :*a work :*rest)
               (rule :*p why does it fail :*rest)
               (rule :*p this code is broken :*rest)
               (rule :*p my code isnt working :*rest)
               (rule :*p my code is not working :*rest)
               (rule :*p troubleshoot :*rest)
               (rule :*p stack trace :*rest)
               (rule :*p runtime error :*rest)
               (rule :*p compile error :*rest)
               (rule :*p syntax error :*rest)]
              (fn [m] (motoko-reply :code/debug m nil
                                    :route :neo :intent :code :use false)))

   ;; ---- Explain
   (make-rule [(rule :*p explain :*a code :*rest)
               (rule :*p explain :*a function :*rest)
               (rule :*p explain :*a method :*rest)
               (rule :*p explain :*a class :*rest)
               (rule :*p explain :*a script :*rest)
               (rule :*p explain :*a module :*rest)
               (rule :*p explain :*a algorithm :*rest)
               (rule :*p explain this :*rest)
               (rule :*p what does :*a code do :*rest)
               (rule :*p what does this function do :*rest)
               (rule :*p what does this do :*rest)
               (rule :*p how does this code work :*rest)
               (rule :*p how does :*a function work :*rest)
               (rule :*p walk me through :*a code :*rest)]
              (fn [m] (motoko-reply :code/explain m nil
                                    :route :neo :intent :code :use false)))

   ;; ---- Refactor / optimize
   (make-rule [(rule :*p refactor :*rest)
               (rule :*p optimize :*a code :*rest)
               (rule :*p optimize :*a function :*rest)
               (rule :*p optimize this :*rest)
               (rule :*p clean up :*a code :*rest)
               (rule :*p clean up this :*rest)
               (rule :*p improve :*a code :*rest)
               (rule :*p improve this :*rest)
               (rule :*p rewrite :*a function :*rest)
               (rule :*p rewrite this :*rest)
               (rule :*p simplify :*a code :*rest)
               (rule :*p simplify this :*rest)]
              (fn [m] (motoko-reply :code/refactor m nil
                                    :route :neo :intent :code :use false)))

   ;; ---- Review
   (make-rule [(rule :*p review :*a code :*rest)
               (rule :*p review :*a pr :*rest)
               (rule :*p review :*a pull request :*rest)
               (rule :*p review this :*rest)
               (rule :*p code review :*rest)
               (rule :*p look at :*a code :*rest)
               (rule :*p check :*a code :*rest)]
              (fn [m] (motoko-reply :code/review m nil
                                    :route :neo :intent :code :use false)))

   ;; ---- Write / create / generate / build / implement (most generic — last)
   (make-rule [(rule :*p write :*a function :*rest)
               (rule :*p write :*a script :*rest)
               (rule :*p write :*a program :*rest)
               (rule :*p write :*a class :*rest)
               (rule :*p write :*a method :*rest)
               (rule :*p write :*a module :*rest)
               (rule :*p write :*a api :*rest)
               (rule :*p write :*a code :*rest)
               (rule :*p create :*a function :*rest)
               (rule :*p create :*a script :*rest)
               (rule :*p create :*a class :*rest)
               (rule :*p create :*a program :*rest)
               (rule :*p create :*a module :*rest)
               (rule :*p create :*a api :*rest)
               (rule :*p generate :*a function :*rest)
               (rule :*p generate :*a script :*rest)
               (rule :*p generate :*a code :*rest)
               (rule :*p generate code :*rest)
               (rule :*p build :*a script :*rest)
               (rule :*p build :*a program :*rest)
               (rule :*p build :*a app :*rest)
               (rule :*p build :*a application :*rest)
               (rule :*p build :*a api :*rest)
               (rule :*p build :*a tool :*rest)
               (rule :*p implement :*a function :*rest)
               (rule :*p implement :*a class :*rest)
               (rule :*p implement :*a method :*rest)
               (rule :*p implement :*a algorithm :*rest)
               (rule :*p implement :*a feature :*rest)
               (rule :*p develop :*a script :*rest)
               (rule :*p develop :*a program :*rest)
               (rule :*p develop :*a feature :*rest)
               (rule :*p code :*a function :*rest)
               (rule :*p code :*a script :*rest)
               (rule :*p code :*a program :*rest)
               (rule :*p code up :*rest)
               (rule :*p one liner :*rest)
               (rule :*p a one liner :*rest)]
              (fn [m] (motoko-reply :code/write m nil
                                    :route :neo :intent :code :use false)))
   ])


;; ---------------------------------------------------------------------------
;; Asimov — creative writing
;; ---------------------------------------------------------------------------

(def asimov-rules
  "Classify story mode (start-chapter / continue / describe-character /
  outline / change-tone) and extract story parameters."
  [])


;; ---------------------------------------------------------------------------
;; Uhura — communication
;; ---------------------------------------------------------------------------

(def uhura-rules
  "Classify communication direction and mode (read/write, email, fetch/filter/send).
  Largest rule group — target 200+ entries."
  [])


;; ---------------------------------------------------------------------------
;; Combined — handed to Motoko's match-all-rules call
;; ---------------------------------------------------------------------------

(def all-rules
  "Full pattern set across all agents. Motoko runs match-all-rules against this.
  Order matters — more specific rules should appear before broader ones."
  (concat motoko-rules
          gandalf-rules
          neo-rules
          asimov-rules
          uhura-rules))

