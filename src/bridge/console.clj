(ns bridge.console
  "CLI REPL for Bridge.

  Read a line from stdin → send to Motoko → print response.
  All intermediate agent/routing/tool output is flushed to stdout wrapped
  in dark-gray ANSI so it recedes visually. The final response is printed
  in the default terminal colour. No GUI dependencies."
  (:require [bridge.motoko :as motoko]
            [bridge.llm    :as llm]
            [clojure.string :as str]))


;; ---------------------------------------------------------------------------
;; ANSI helpers — no external deps, pure escape sequences
;; ---------------------------------------------------------------------------

(def ^:private GRAY  "\033[90m")   ; dark gray  — intermediate chatter
(def ^:private BOLD  "\033[1m")    ; bold       — labels
(def ^:private CYAN  "\033[36m")   ; cyan       — agent name
(def ^:private RESET "\033[0m")    ; reset all attributes

(def ^:private DIM   "\033[2m")    ; dim        — cwd path in prompt


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
                    (str DIM " [" (shorten-path @cwd-atom) "]" RESET BOLD))]
    (str BOLD CYAN label RESET BOLD (or cwd-part "") " › " RESET)))


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
   (println (str BOLD CYAN "Bridge" RESET " — type 'exit' or Ctrl-D to quit\n"))
   (loop []
     (print (build-prompt))
     (flush)
     (when-let [raw (read-line)]
       (let [text (str/trim raw)]
         (when-not (= text "exit")
           (when-not (str/blank? text)
             ;; Switch terminal to gray — all intermediate println calls land here
             (print GRAY)
             (flush)
             (let [reply (try
                           (binding [llm/*api-key* (or api-key llm/*api-key*)]
                             (motoko/motoko text))
                           (catch Throwable t
                             {:msg/from :motoko
                              :response (str "[error: " (.getMessage t) "]")}))]
               ;; Reset color, ensure we start on a fresh line, then print reply
               (print RESET)
               (flush)
               (println)
               (println (str BOLD CYAN (agent-label reply) RESET
                             " › " (reply-text reply)))
               (println)))
           (recur)))))))
