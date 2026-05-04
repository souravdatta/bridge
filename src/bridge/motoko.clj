(ns bridge.motoko
  (:require [bridge.protocol :as proto]
            [bridge.rules    :as rules]
            [bridge.patterns :as patterns]
            [bridge.llm      :as llm]
            [bridge.memory   :as memory]
            [bridge.neo      :as neo]
            [bridge.gandalf  :as gandalf]
            [bridge.asimov   :as asimov]
            [bridge.uhura    :as uhura]
            [bridge.quorra   :as quorra]
            [clojure.string  :as str]
            [cheshire.core   :as json]))

(def ^:private motoko-name
  "Display name for this agent (used in dialogs, logs, etc.)."
  "Motoko")

;; Motoko — Master Orchestrator.
;;
;; Dispatch flow:
;;   1. Run rules/match-all-rules on patterns/all-rules against user input.
;;   2. On match  → context map {:route :agent :action ... :bindings ... :original ...}
;;                → dispatch to the correct boss agent.
;;   3. On nil    → call LLM with a tightly-scoped routing prompt.
;;                → re-dispatch on resolved intent.
;;
;; Also owns the shared blackboard atom — a short-term context store
;; readable by all boss agents (last N exchanges, last code snippet,
;; last story state, last search results, etc.).

;; ---------------------------------------------------------------------------
;; Followup interpreter — resolves dynamic s-expressions produced by rules
;; into concrete response strings.
;;   (:time :now)                   current time (HH:mm:ss)
;;   (:time (:diff seconds))        now +/- seconds (HH:mm:ss on yyyy-MM-dd)
;;   (:date :today)                 today (EEEE, d MMMM yyyy)
;;   (:date (:diff days))           today +/- days, same format
;;   (:agent :kw "prompt")          dispatch prompt to agent :kw via
;;                                  *agent-dispatch*
;;
;; apply-followups maps resolve-followup over every entry and returns a
;; flat vector of response strings. Chain walking (an agent reply carrying
;; its own followups) is left for a later pass.
;; ---------------------------------------------------------------------------

(def ^:private time-fmt
  (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss"))

(def ^:private datetime-fmt
  (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss 'on' yyyy-MM-dd"))

(def ^:private date-fmt
  (java.time.format.DateTimeFormatter/ofPattern "EEEE, d MMMM yyyy"))

;; ---------------------------------------------------------------------------
;; Agent dispatch — map of :keyword -> entry fn (same name as each agent ns).
;; Used by both the :route dispatch below and the (:agent ...) followup form.
;; ---------------------------------------------------------------------------

(def ^:private agent-dispatch-map
  {:neo     neo/neo
   :gandalf gandalf/gandalf
   :asimov  asimov/asimov
   :uhura   uhura/uhura
   :quorra  quorra/quorra})

;; ---------------------------------------------------------------------------
;; Active-agent stickiness.
;; Starts at :motoko. Any successful routing flips it to that agent, so
;; subsequent turns bypass Motoko entirely. The user switches back (or to
;; any other agent) with a slash command: /motoko, /neo, /gandalf, etc.
;; ---------------------------------------------------------------------------

(defonce ^:private active-agent (atom :motoko))

(def ^:private known-agents
  #{:motoko :neo :gandalf :asimov :uhura :quorra})

(def ^:private switch-messages
  {:motoko  "I have the bridge. What do you need?"
   :neo     "Neo takes the line. Code mode engaged."
   :gandalf "Gandalf on duty. Calendar is ready."
   :asimov  "Asimov at the archives. What are we researching?"
   :uhura   "Uhura at comms. Speak."
   :quorra  "Quorra here. Whatever you need — I'm on it."})

(defn- slash-command? [s]
  (and (string? s)
       (let [t (str/trim s)]
         (and (pos? (count t))
              (= \/ (.charAt t 0))))))

(defn- parse-slash
  "Parse a /agent-name command. Returns a known agent keyword, or nil."
  [s]
  (let [token (-> s str/trim (subs 1) str/lower-case (str/split #"\s+") first)]
    (when (seq token)
      (let [kw (keyword token)]
        (when (known-agents kw) kw)))))

(defn- switch-agent!
  "Set active-agent and return a :reply envelope confirming the switch."
  [target request]
  (reset! active-agent target)
  (proto/make-reply request
                    :from :motoko
                    :response (get switch-messages target
                                   (str "Active agent: " (name target) "."))
                    :use true
                    :intent :meta
                    :action :switch-agent))

(defn- redact-self
  "Remove self-references from a prompt before handing it to another agent."
  [s]
  (when s (str/replace s #"(?i)motoko" "")))

(def ^:dynamic *agent-dispatch*
  "Function [(agent-kw prompt) -> reply-string] called for (:agent ...)
  followups. Default builds a :request envelope, dispatches it through
  agent-dispatch-map, and returns the agent's :response string."
  (fn [agent-kw prompt]
    (if-let [f (get agent-dispatch-map agent-kw)]
      (let [req   (proto/make-request :from :motoko :to agent-kw
                                      :content (redact-self prompt))
            reply (f req)]
        (:response reply))
      (str "[unknown agent: " agent-kw "]"))))

;; ---------------------------------------------------------------------------
;; Forwarding messages — what Motoko says as she hands work off.
;; ---------------------------------------------------------------------------

(def ^:private forward-lines
  {:neo     ["Neo — you're up. Take it from here."
             "Routing to Neo. Keep me posted."
             "Neo, your kind of problem. Go."
             "Passing this to Neo. Try not to over-engineer."]
   :gandalf ["Gandalf. This one's yours — stay on schedule."
             "Handing off to Gandalf. Time is his element, not mine."
             "Routing to Gandalf. Calendar duty."]
   :asimov  ["Asimov — dig deep. Bring receipts."
             "Handing research to Asimov. He'll come back with footnotes."
             "Passing to Asimov. Take the long path."
             "Asimov, go find something true."]
   :uhura   ["Uhura, open a channel."
             "Routing to Uhura. Mind the send guard."
             "Uhura — comms duty."]
   :quorra  ["Quorra — they need you. Go."
             "Passing to Quorra. She'll find a way."
             "Quorra, take point. Bring them in."
             "Routing to Quorra. She doesn't stop."]})

(defn- forward-message [agent-kw]
  (rand-nth (get forward-lines agent-kw
                 [(str "Routing to " (name agent-kw) ".")])))

(defn- forward-to-agent
  "Announce the handoff, update active-agent, redact self-references from
  :content, and call the target agent's entry fn. Takes a :request envelope
  addressed to the target (use proto/forward to build it). Returns the
  agent's :reply envelope verbatim, or a synthesised :error reply if no
  dispatcher is registered for the target."
  [request]
  (let [target (:msg/to request)
        req'   (update request :content redact-self)]
    (println (forward-message target))
    (reset! active-agent target)
    (if-let [f (get agent-dispatch-map target)]
      (f req')
      (proto/make-reply request
                        :from :motoko
                        :response (str "[no dispatcher for " target "]")
                        :use true
                        :status :error
                        :error {:type :no-dispatcher :target target}))))

(defn- diff? [x]
  (and (coll? x) (= (first x) :diff)))

(defn- resolve-followup
  "Interpret one followup s-expression. Returns a string."
  [expr]
  (let [[op arg1 arg2] expr]
    (cond
      (and (= op :time) (= arg1 :now))
      (.format (java.time.LocalTime/now) time-fmt)

      (and (= op :time) (diff? arg1))
      (let [secs (second arg1)
            t    (.plusSeconds (java.time.LocalDateTime/now) secs)]
        (.format t datetime-fmt))

      (and (= op :date) (= arg1 :today))
      (.format (java.time.LocalDate/now) date-fmt)

      (and (= op :date) (diff? arg1))
      (let [days (second arg1)
            d    (.plusDays (java.time.LocalDate/now) days)]
        (.format d date-fmt))

      (= op :agent)
      (*agent-dispatch* arg1 arg2)

      :else (str "unresolved followup: " (pr-str expr)))))

(defn apply-followups
  "Resolve every :followup entry and store the flat vector of strings as
  :response, flipping :use to true. No-op when :followup is empty."
  [env]
  (if (seq (:followup env))
    (assoc env
           :response (mapv resolve-followup (:followup env))
           :use true)
    env))

;; ---------------------------------------------------------------------------
;; Omni consultant — LLM fallback when no pattern matches.
;; Motoko asks the Hiranyagarbha (the LLM) to pick an agent. A dedicated
;; session carries the routing system prompt. The LLM must return strict
;; JSON; we parse it and forward through the standard agent dispatch.
;; ---------------------------------------------------------------------------

(def ^:private omni-system-prompt
  (str
"You are a precise intent-routing classifier for the Section 9 agent system.
Given a user prompt, decide which single agent should handle it.

Respond ONLY with a single JSON object. No prose, no markdown fences, no
backticks, no explanations. Just the JSON on one line.

Schema:
  {\"route\": \"<agent>\", \"intent\": \"<kw>\", \"action\": \"<kw>\"}

Agents (the ONLY valid values for \"route\"):
" proto/roster-text "

Field rules:
  route  — MUST be one of: motoko, neo, gandalf, asimov, uhura, quorra.
  intent — short category keyword (examples: code, schedule, story,
           research, comms, greetings, general).
  action — narrower kind inside that intent (examples: write, debug,
           new, recall, summarise, help).

Time- and date-related questions ALWAYS route to gandalf — including
natural-language number forms the pattern engine could not parse
(\"in two days\", \"three hours ago\", \"next week\", \"a fortnight\",
\"tomorrow at noon\", \"this Friday\"). If the prompt reached you and
it is about time, date, calendar, duration, or scheduling in any form,
return \"route\": \"gandalf\". Do NOT send such prompts to quorra or
motoko.

motoko handles ONLY simple greetings, small talk, and personal chit
chat. Do NOT route time/date questions to motoko even though the
roster line mentions them — the pattern layer handles the easy ones
before reaching you; if they reached you, send them to gandalf.

If the prompt does not clearly match any of neo / gandalf / asimov /
uhura — route to quorra. Quorra is the generalist: open-ended
conversation, brainstorming, planning, philosophy, creative ideation.
Route long-form deep-research / multi-source synthesis requests to
asimov, NOT quorra.

Return the JSON object and nothing else."))

(def ^:private omni-session
  (llm/get-session :name "motoko-omni" :system omni-system-prompt))

(def ^:private omni-log-lines
  ["Patterns came up empty. Consulting the Hiranyagarbha."
   "My rules don't cover this one. Pinging the higher mind."
   "Fall-back engaged. Asking Hiranyagarbha to route this."
   "Reaching out to the oracle. Stand by. And don't play with me while I am gone ;-)"])

(defn- parse-omni-response
  "Pull the JSON object out of an LLM reply and coerce to keyword values.
  Returns nil if the reply can't be parsed."
  [s]
  (try
    (let [cleaned (-> s
                      str/trim
                      (str/replace #"^```(?:json)?\s*" "")
                      (str/replace #"\s*```$" ""))
          parsed  (json/parse-string cleaned true)]
      (when (and (:route parsed) (:intent parsed))
        {:route  (keyword (:route parsed))
         :intent (keyword (:intent parsed))
         :action (some-> (:action parsed) keyword)}))
    (catch Exception _ nil)))

(defn consult-omni
  "Called when pattern matching returns nil. Asks the LLM to classify the
  prompt and forwards to the chosen agent. On parse failure or unknown
  route, falls through to Quorra. Takes a :request envelope, returns a
  :reply envelope."
  [request]
  (println (rand-nth omni-log-lines))
  (let [reply    (llm/chat omni-session (:content request))
        decision (parse-omni-response reply)]
    (if (and decision
             (get agent-dispatch-map (:route decision)))
      (forward-to-agent
        (proto/forward request (:route decision)
                       :intent (:intent decision)
                       :action (:action decision)))
      ;; Parse failed or unknown route -> Quorra as catch-all.
      (forward-to-agent
        (proto/forward request :quorra
                       :intent :general
                       :action :help)))))


(defn- handle-classification
  "Convert a pattern-match classification map into the appropriate envelope:
   - route :motoko with :followup  -> reply envelope, apply-followups resolves.
   - route :motoko with :response  -> reply envelope with canned text.
   - route :<other>                -> forwarded request, dispatched immediately."
  [classification request]
  (let [target (:route classification)]
    (cond
      (and (= target :motoko)
           (seq (:followup classification))
           (nil? (:response classification)))
      (apply-followups
        (proto/make-reply request
                          :from :motoko
                          :response nil
                          :use false
                          :followup (:followup classification)
                          :intent (:intent classification)
                          :action (:action classification)))

      (= target :motoko)
      (proto/make-reply request
                        :from :motoko
                        :response (:response classification)
                        :use (get classification :use true)
                        :followup (:followup classification [])
                        :intent (:intent classification)
                        :action (:action classification))

      :else
      (forward-to-agent
        (proto/forward request target
                       :intent (:intent classification)
                       :action (:action classification)
                       :bindings (:bindings classification)
                       :followup (:followup classification []))))))


(defn motoko
  "Main entry. Accepts a raw user string OR a :request envelope. Always
  returns a :reply envelope.

  Dispatch order:
   1. Slash command (/motoko, /neo, ...) — switch active-agent, confirm.
   2. active-agent != :motoko — bypass rules, forward to active agent.
   3. active-agent == :motoko — run pattern engine; on match, dispatch via
      handle-classification; on miss, consult-omni."
  [input]
  (let [request (cond
                  (and (map? input) (= :request (:msg/kind input)))
                  input

                  (string? input)
                  (proto/make-request :from :user :to :motoko :content input)

                  :else
                  (proto/make-request :from :user :to :motoko :content (str input)))
        content (:content request)]
    (cond
      (slash-command? content)
      (if-let [target (parse-slash content)]
        (switch-agent! target request)
        (proto/make-reply request
                          :from :motoko
                          :response (str "Unknown slash command: " content
                                         ". Known: "
                                         (str/join ", "
                                                   (map #(str "/" (name %))
                                                        known-agents)))
                          :use true
                          :status :error
                          :intent :meta
                          :action :unknown-command))

      (not= :motoko @active-agent)
      (forward-to-agent (proto/forward request @active-agent))

      :else
      (if-let [classification (rules/match-all-rules patterns/all-rules content)]
        (handle-classification classification request)
        (consult-omni request)))))

