(ns bridge.console
  "CLI REPL for Bridge.

  Read a line from stdin → send to Motoko → print response.
  All intermediate agent/routing/tool output is flushed to stdout without styling.
  The final response is printed in the default terminal colour. No GUI dependencies."
  (:require [bridge.motoko :as motoko]
            [bridge.llm    :as llm]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Banner for agent messages and ask-user tool calls
;; ---------------------------------------------------------------------------

(def ^:private AGENT-BANNER-TOP "[[ AGENT >>")
(def ^:private AGENT-BANNER-BOTTOM "]]\n")
(def ^:private ASK-USER-BANNER-TOP "[[ ASK USER >>\n") 
(def ^:private ASK-USER-BANNER-BOTTOM "]]\n")


;; ---------------------------------------------------------------------------
;; Dynamic prompt
;; ---------------------------------------------------------------------------

(defn- shorten-path
  "Replace the user's home directory prefix with ~ in PATH-STR."
  [path-str]
  (let [home (System/getProperty "user.home")]
    (if (str/starts-with? path-str home)
      (str "~" (subs path-str (count home)))
      path-str)))

(defn- build-prompt
  "Build the REPL input prompt reflecting the active agent and its CWD.
  Format: <Agent> [<cwd>] › when the agent has a tracked directory.
           <Agent> ›       for agents without a CWD atom."
  []
  (let [agent     @motoko/active-agent
        label     (-> agent name str/capitalize)
        cwd-atom  (get motoko/agent-cwd-atoms agent)
        cwd-part  (when cwd-atom
                    (str " [" (shorten-path @cwd-atom) "]"))]
    (str label (or cwd-part "") " › ")))


(defn- reply-text [reply]
  (cond
    (nil? reply)                       "[no reply]"
    (and (map? reply) (:response reply)) (str (:response reply))
    (string? reply)                    reply
    :else                              (pr-str reply)))

(defn- agent-label [reply]
  (let [from (and (map? reply) (:msg/from reply))]
    (cond
      (= from :ghost) "Motoko's ghost"
      (keyword? from) (-> from name str/capitalize)
      (string? from)  from
      :else           "Motoko")))


;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn start
  "Start the CLI REPL. Blocks until the user types 'exit' or sends EOF.

  Optional API-KEY overrides bridge.llm/*api-key* for each Motoko call.
  Falls back to the existing dynamic var or XAI_API_KEY env var."
  ([] (start nil))
  ([api-key]
   (println "Bridge — type 'exit' or Ctrl-D to quit\n")
   (loop []
     (print (build-prompt))
     (flush)
     (when-let [raw (read-line)]
       (let [text (str/trim raw)]
         (when-not (= text "exit")
           (when-not (str/blank? text)
             ;; No styling for intermediate output
             (flush)
             (let [reply (try
                           (binding [llm/*api-key* (or api-key llm/*api-key*)]
                             (motoko/motoko text))
                           (catch Throwable t
                             {:msg/from :motoko
                              :response (str "[error: " (.getMessage t) "]")}))]
               ;; No styling reset, ensure fresh line, then print reply with banners
               (flush)
               (println)
               (if (and (map? reply) (:tool-call reply) (= (:tool-call reply) :ask-user))
                 (do
                   (println ASK-USER-BANNER-TOP)
                   (println (str (agent-label reply) 
                                 " › " (reply-text reply)))
                   (println ASK-USER-BANNER-BOTTOM))
                 (do
                   (println AGENT-BANNER-TOP)
                   (println (str (agent-label reply) 
                                 " › " (reply-text reply)))
                   (println AGENT-BANNER-BOTTOM)))
               (println)))
           (recur)))))))
