(ns bridge.core
  (:require
   [bridge.llm :as llm]
   [bridge.motoko :as m]))

(def x-api-api-key "")


(defn motoko- [prompt]
  (binding [llm/*api-key* x-api-api-key]
    (m/motoko prompt)))

(comment


  (m/motoko "Motoko I love you")

  (m/motoko "Hey motoko good afternoon")

  (binding [llm/*api-key* x-api-api-key]
    (m/motoko "Need to build a new OS from scratch"))

  (binding [llm/*api-key* x-api-api-key]
    (m/motoko "How shall we get started?"))

  (m/motoko "/motoko")

  (binding [llm/*api-key* x-api-api-key]
    (m/motoko "What is the meaning of this all, really?"))

  (m/motoko "/uhura")

  (m/motoko "Have I got new messages?")

  (m/motoko "/motoko")

  (binding [llm/*api-key* x-api-api-key]
    (m/motoko "How is my beautiful program doing?"))

  (m/motoko "/motoko")

  (binding [llm/*api-key* x-api-api-key]
    (m/motoko "what is the time in two days?"))

  (m/motoko "What date is today?")

  (m/motoko "Hi lovely")
  
  (motoko- "/quorra")

  (motoko- "What is the date after 10 days from today?")

  (motoko- "/gandalf")
  
  (motoko- "I want you")
  
  
  
  )