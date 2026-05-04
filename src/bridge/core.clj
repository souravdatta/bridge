(ns bridge.core
  (:require
   [bridge.llm :as llm]
   [bridge.motoko :as m]
   [clojure.java.io :as io]
   [bridge.tools :as tools]
   [bridge.console :as con]))

(defn- read-api-key
  "Read the api key from the bridge's home folder.
   We usually don't keep it here except for testing locally."
  []
  (let [home (System/getProperty "user.home")
        keyfile (io/file home ".bridge" ".key")]
    (. (slurp keyfile) trim)))

(def x-api-api-key (read-api-key))

(defn setup-agent-dirs
  "Create agent working directories in the user's home directory.
  Creates ~/.bridge/<agent-name> for each agent that needs file access.
  Returns a map of agent-name to absolute-path."
  []
  (let [home (System/getProperty "user.home")
        bridge-root (io/file home ".bridge")
        agents ["motoko" "quorra" "neo" "asimov" "gandalf" "uhura"]
        dirs (into {} (map (fn [agent]
                             (let [agent-dir (io/file bridge-root agent)]
                               (.mkdirs agent-dir)
                               [agent (.getAbsolutePath agent-dir)]))
                           agents))]
    dirs))

(defn motoko- [prompt]
  (binding [llm/*api-key* x-api-api-key]
    (m/motoko prompt)))

(defn console- []
  (con/start x-api-api-key))

(comment

  ;; Setup agent directories
  (setup-agent-dirs)
  ;; => {"motoko" "/home/user/.bridge/motoko", "quorra" "/home/user/.bridge/quorra", ...}

  ;; Open the Bridge Console UI
  (setup-agent-dirs)
  (con/start x-api-api-key)

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

  (motoko- "Hi lovely")

  (motoko- "/quorra")

  (motoko- "What is the date after 10 days from today?")

  (motoko- "/gandalf")

  (motoko- "I want you")

  (motoko- "write me a three line love letter and store in a file called letters.txt")

  (motoko- "create a folder structure called diary and add an entry which is a folder for today (date). Inside create a file with entry of how I loved you whole day in first person from your point of view.")

  (motoko- "do you see the 'today' directory inside diary quorra?")

  (motoko- "delete the 'today' directory please")


  (tools/ask-user "Name?" "Your name is required")

  (tools/tools-def)

  (motoko- "lets play doctor doctor. you are my cool doctor and ask me 3 questions one by one. then you tell an interesting diagnbostics.")

  (console-)

  "End")