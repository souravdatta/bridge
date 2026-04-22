(ns bridge.sub-agent-factory
  (:require [bridge.protocol :as proto]))

;; Sub-agent factory.
;; Creates on-demand worker agents for boss agents to delegate to.
;; All sub-agents implement bridge.protocol/Agent so they inherit
;; the same pattern-first + narrow-LLM behaviour automatically.
;;
;; Known sub-agent types:
;;   :code-runner       (Neo)    — executes generated code, requires confirmation
;;   :test-runner       (Neo)    — runs test suite, returns output
;;   :email-reader      (Uhura)  — IMAP fetch via postal / javax.mail
;;   :email-sender      (Uhura)  — SMTP send via postal
;;   :summariser        (Uhura, Asimov) — LLM summarisation
;;   :task-extractor    (Uhura)  — LLM extracts action items from content
;;   :web-searcher      (Asimov) — Wikipedia API / pluggable search
;;   :citation-tracker  (Asimov) — footnotes / source registry
;;   :note-logger       (Asimov) — appends to data/asimov-library.edn

(defn hello
  "Hello from bridge.sub-agent-factory!"
  []
  (println "Hello from bridge.sub-agent-factory!"))
