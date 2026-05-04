(ns bridge.uhura
  (:require [bridge.protocol :as proto]
            [bridge.rules    :as rules]
            [bridge.llm      :as llm]
            [bridge.memory   :as memory]))

(def ^:private uhura-name
  "Display name for this agent (used in dialogs, logs, etc.)."
  "Uhura")

;; Uhura — Communicator Boss.
;; Large pattern corpus (200+ rules in resources/patterns/uhura.edn) handles
;; all communication mechanics: read/write, email/WhatsApp, to/from whom.
;;
;; Classical path (no LLM): fetch emails, filter by sender/date, check replies.
;; LLM path: summarise fetched content, extract tasks, compose message bodies.
;;
;; Sub-agents: email-reader, email-sender, summariser, task-extractor.
;;
;; SEND GUARD: any outbound action requires explicit user confirmation.
;;             Pattern match alone is never sufficient to trigger a send.
;;
;; WhatsApp: deferred — no viable personal API at this time.
;;
;; LLM usage: medium.

(defn uhura
  "Entry point from Motoko. Takes a :request envelope, returns a :reply
  envelope. Stub for now."
  [request]
  (proto/make-reply request
                    :response (str "Uhura here. I will take care of: "
                                   (pr-str (:content request)))
                    :use true
                    :status :ok))


(defn hello
  "Hello from bridge.uhura!"
  []
  (println "Hello from bridge.uhura!"))
