(ns bridge.asimov
  (:require [bridge.protocol :as proto]
            [bridge.rules    :as rules]
            [bridge.llm      :as llm]
            [bridge.memory   :as memory]))

(def ^:private asimov-name
  "Display name for this agent (used in dialogs, logs, etc.)."
  "Asimov")

;; Asimov — Deep Research Boss.
;; Long-form investigation, synthesis, footnoted analysis. Takes the heavy
;; research lifting; Quorra handles casual brainstorming and light ideation.
;;
;; Pattern engine identifies mode: scan / synthesise / connect / critique /
;; summarise. Sub-agents (pending): web-searcher, citation-tracker,
;; note-logger. Persistent store (pending): data/asimov-library.edn
;;
;; LLM usage: high and expected.

(defn asimov
  "Entry point from Motoko. Takes a :request envelope, returns a :reply
  envelope. Stub for now."
  [request]
  (proto/make-reply request
                    :response (str "Asimov here. I will take care of: "
                                   (pr-str (:content request)))
                    :use true
                    :status :ok))


(defn hello
  "Hello from bridge.asimov!"
  []
  (println "Hello from bridge.asimov!"))
