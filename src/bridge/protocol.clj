(ns bridge.protocol
  (:require [clojure.string     :as str]
            [clojure.spec.alpha :as s]))

;; Agent protocol — implemented by Motoko, all boss agents, and sub-agents.
;; Every agent in the bridge system satisfies this protocol.

(defprotocol Agent
  (process           [this input]           "Main entry: pattern-first → optional LLM → action")
  (clarify-intention [this input]           "Narrow LLM call: returns EDN {:resolved-intent :keyword :params {}}")
  (create-sub-agent  [this task-type params] "Spawn an on-demand worker agent; returns a new Agent record"))


;; ---------------------------------------------------------------------------
;; Shared agent roster — the bosses of Section 9. Both Motoko (for her
;; routing classifier) and Quorra (for her suggest-an-agent mode) embed this
;; data in their LLM system prompts. Kept here to avoid a cross-dependency
;; between the two.
;; ---------------------------------------------------------------------------

(def agent-roster
  "Ordered vector of agent entries. Each: {:key :kw :desc \"…\"}."
  [{:key :motoko  :desc "greetings, small talk, personal chit chat"}
   {:key :neo     :desc "code: write, fix, debug, refactor, explain, review, test"}
   {:key :gandalf :desc "scheduling, reminders, calendar, time/date queries, daily planning"}
   {:key :asimov  :desc "deep research, long-form investigation, synthesis, footnoted analysis"}
   {:key :uhura   :desc "communications: email, messages, reading / sending"}
   {:key :quorra  :desc "generalist: conversation, brainstorming, planning, philosophy, creative ideation"}])

(def roster-text
  "String rendering of agent-roster — ready to embed in LLM system prompts."
  (let [pad (reduce max (map (comp count name :key) agent-roster))]
    (str/join "\n"
              (map (fn [{:keys [key desc]}]
                     (str "  "
                          (format (str "%-" pad "s") (name key))
                          " — "
                          desc))
                   agent-roster))))


;; ---------------------------------------------------------------------------
;; Envelope protocol — shared data contract for user↔agent and agent↔agent
;; communication. Three kinds: :request, :reply, :event. Every envelope
;; carries a :msg/* header for addressing and correlation, plus a kind-
;; specific payload. Consumers should build envelopes via make-request /
;; make-reply / make-event / forward rather than assembling maps by hand.
;; ---------------------------------------------------------------------------

(def msg-kinds
  "All valid envelope kinds."
  #{:request :reply :event})

(def msg-statuses
  "All valid :status values on a :reply envelope."
  #{:ok :error :deferred :forwarded})

(def known-agents
  "Addressable principals. Includes :user and :system in addition to agents."
  #{:user :system :motoko :neo :gandalf :asimov :uhura :quorra})

;; --- Envelope header specs -------------------------------------------------
(s/def :msg/id          uuid?)
(s/def :msg/kind        msg-kinds)
(s/def :msg/from        known-agents)
(s/def :msg/to          known-agents)
(s/def :msg/in-reply-to (s/nilable uuid?))
(s/def :msg/thread-id   uuid?)
(s/def :msg/timestamp   inst?)

(s/def ::envelope
  (s/keys :req [:msg/id :msg/kind :msg/from :msg/to
                :msg/in-reply-to :msg/thread-id :msg/timestamp]))

;; --- Helpers ---------------------------------------------------------------
(defn fresh-msg-id    [] (java.util.UUID/randomUUID))
(defn fresh-thread-id [] (java.util.UUID/randomUUID))
(defn now             [] (java.time.Instant/now))

;; --- Builders --------------------------------------------------------------
(defn make-request
  "Build a :request envelope.
  Required kwargs: :from, :to.
  Optional: :thread-id (fresh if omitted), :content, :intent, :action,
            :bindings, :followup, :context, :in-reply-to."
  [& {:keys [from to thread-id content intent action bindings followup context
             in-reply-to]
      :or   {followup [] bindings {} context {}}}]
  {:msg/id          (fresh-msg-id)
   :msg/kind        :request
   :msg/from        from
   :msg/to          to
   :msg/in-reply-to in-reply-to
   :msg/thread-id   (or thread-id (fresh-thread-id))
   :msg/timestamp   (now)
   :content         content
   :intent          intent
   :action          action
   :bindings        bindings
   :followup        followup
   :context         context})

(defn make-reply
  "Build a :reply envelope tied to `request`.
  Optional: :response, :use (true), :status (:ok), :followup ([]),
            :error, :intent, :action, :from (defaults to request :msg/to)."
  [request & {:keys [response use status followup error from intent action]
              :or   {use true status :ok followup [] error nil}}]
  {:msg/id          (fresh-msg-id)
   :msg/kind        :reply
   :msg/from        (or from (:msg/to request))
   :msg/to          (:msg/from request)
   :msg/in-reply-to (:msg/id request)
   :msg/thread-id   (:msg/thread-id request)
   :msg/timestamp   (now)
   :response        response
   :use             use
   :status          status
   :followup        followup
   :error           error
   :intent          intent
   :action          action})

(defn make-event
  "Build an :event envelope. :from, :to, :event required."
  [& {:keys [from to thread-id event payload]
      :or   {payload {}}}]
  {:msg/id          (fresh-msg-id)
   :msg/kind        :event
   :msg/from        from
   :msg/to          to
   :msg/in-reply-to nil
   :msg/thread-id   (or thread-id (fresh-thread-id))
   :msg/timestamp   (now)
   :event           event
   :payload         payload})

(defn forward
  "Re-address an existing request to `target`. Preserves :msg/thread-id,
  mints a new :msg/id and :msg/timestamp, swaps :msg/from→old :msg/to and
  :msg/to→target. Optional kwargs override request payload fields."
  [request target & {:keys [content intent action bindings followup context]}]
  (cond-> (assoc request
                 :msg/id        (fresh-msg-id)
                 :msg/from      (:msg/to request)
                 :msg/to        target
                 :msg/timestamp (now))
    (some? content)  (assoc :content content)
    (some? intent)   (assoc :intent intent)
    (some? action)   (assoc :action action)
    (some? bindings) (assoc :bindings bindings)
    (some? followup) (assoc :followup followup)
    (some? context)  (assoc :context context)))
