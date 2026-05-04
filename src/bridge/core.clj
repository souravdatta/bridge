(ns bridge.core
  (:gen-class)
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


(defn setup-agent-dirs
  "Create agent working directories in the user's home directory.
  Creates ~/.bridge/<agent-name> for each agent that needs file access.
  Returns a map of agent-name to absolute-path."
  []
  (let [home (System/getProperty "user.home")
        bridge-root (io/file home ".bridge")
        agents ["motoko" "ghost" "neo" "asimov" "gandalf" "uhura"]
        dirs (into {} (map (fn [agent]
                             (let [agent-dir (io/file bridge-root agent)]
                               (.mkdirs agent-dir)
                               [agent (.getAbsolutePath agent-dir)]))
                           agents))]
    dirs))


(defn -main [& _]
  (let [api-key (read-api-key)]
    (setup-agent-dirs)
    (con/start api-key)))
