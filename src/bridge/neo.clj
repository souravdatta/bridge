(ns bridge.neo
  (:require [bridge.protocol :as proto]
            [bridge.rules    :as rules]
            [bridge.llm      :as llm]
            [bridge.memory   :as memory]))

;; Neo — Coding Boss.
;; Pattern engine classifies task type (generate / fix / refactor / explain / run-tests)
;; and extracts language + description. LLM does the actual work.
;;
;; Sub-agents:
;;   code-runner  — executes generated code via clojure.java.shell
;;                  REQUIRES explicit user confirmation before running
;;   test-runner  — runs existing tests, returns output
;;
;; LLM usage: high and expected.

(defn neo
  "Entry point from Motoko. Takes a :request envelope, returns a :reply
  envelope (see bridge.protocol). Stub for now."
  [request]
  (proto/make-reply request
                    :response (str "Neo here. I will take care of: "
                                   (pr-str (:content request)))
                    :use true
                    :status :ok))


(defn hello
  "Hello from bridge.neo!"
  []
  (println "Hello from bridge.neo!"))
