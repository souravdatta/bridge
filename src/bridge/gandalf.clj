(ns bridge.gandalf
  (:require [bridge.protocol :as proto]
            [bridge.rules    :as rules]
            [bridge.memory   :as memory]))

(def ^:private gandalf-name
  "Display name for this agent (used in dialogs, logs, etc.)."
  "Gandalf")

;; Gandalf — Daily Planner Boss.
;; No rules engine — plain cond/case dispatch on slots extracted by Motoko's
;; pattern match. Quartzite handles job scheduling. Natty parses natural
;; date expressions ("next Tuesday", "in 3 weeks").
;;
;; LLM usage: near zero.

(defn gandalf
  "Entry point from Motoko. Takes a :request envelope, returns a :reply
  envelope. Stub for now."
  [request]
  (proto/make-reply request
                    :response (str "Gandalf here. I will take care of: "
                                   (pr-str (:content request)))
                    :use true
                    :status :ok))


(defn hello
  "Hello from bridge.gandalf!"
  []
  (println "Hello from bridge.gandalf!"))
