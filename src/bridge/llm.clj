(ns bridge.llm
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

;; xAI Grok LLM client (OpenAI-compatible REST).
;; - Stateless chat-request for one-shot calls
;; - Named sessions with per-session locks for serialized, thread-safe use
;; - Tool/function calling loop
;; - Hard cap on history to prevent runaway growth
;;
;; Env vars:
;;   XAI_API_KEY  (required)
;;   XAI_MODEL    (optional, defaults to "grok-3-latest")
;;
;; Body opts are passed through verbatim to the JSON body, so use the
;; API's own key names (e.g. :max_tokens, :tool_choice, :top_p).


;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(def ^:private api-base "https://api.x.ai/v1")

(def ^:private max-history
  "Hard cap on total messages kept per session. System prompt is always
  preserved; oldest non-system messages are dropped when exceeded."
  80)

(def ^:private default-tool-iters 6)

(def ^:dynamic *api-key*
  "Override XAI_API_KEY for the current dynamic extent. Use with `binding`."
  nil)

(def ^:dynamic *model*
  "Override XAI_MODEL for the current dynamic extent. Use with `binding`."
  nil)

(defn- api-key []
  (or *api-key*
      (System/getenv "XAI_API_KEY")
      (throw (ex-info "XAI_API_KEY is not set" {}))))

(defn- default-model []
  (or *model*
      (System/getenv "XAI_MODEL")
      "grok-3-latest"))


;; ---------------------------------------------------------------------------
;; Message constructors
;; ---------------------------------------------------------------------------

(defn sys-msg  [content] {:role "system"    :content content})
(defn user-msg [content] {:role "user"      :content content})
(defn asst-msg [content] {:role "assistant" :content content})

(defn tool-msg
  "Result of a tool invocation, keyed back to the model's tool_call_id."
  [tool-call-id result]
  {:role "tool" :tool_call_id tool-call-id :content (str result)})

(defn tool-spec
  "Build a function-tool declaration. params-schema is JSON Schema."
  [fn-name description params-schema]
  {:type "function"
   :function {:name fn-name
              :description description
              :parameters params-schema}})


;; ---------------------------------------------------------------------------
;; Raw HTTP call
;; ---------------------------------------------------------------------------

(defn chat-request
  "POST /chat/completions. opts becomes the JSON body; :messages required.
  :model defaults to (default-model). Returns parsed body on 2xx,
  throws ex-info otherwise."
  [opts]
  (let [body (merge {:model (default-model)} opts)
        resp (http/post (str api-base "/chat/completions")
                        {:headers {"Authorization" (str "Bearer " (api-key))
                                   "Content-Type"  "application/json"}
                         :body (json/generate-string body)
                         :as :json
                         :throw-exceptions false
                         :coerce :always
                         :cookie-policy :ignore})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info "xAI chat request failed"
                      {:status (:status resp)
                       :body   (:body resp)})))))


;; ---------------------------------------------------------------------------
;; Sessions
;; ---------------------------------------------------------------------------

(defonce ^:private registry (atom {}))

(defn- initial-messages [system]
  (if system [(sys-msg system)] []))

(defn- make-session [name system]
  {:name     name
   :system   system
   :messages (atom (initial-messages system))
   :lock     (Object.)})

(defn get-session
  "Fetch (creating if absent) a session for the given agent name.
  :system is applied only on creation; ignored for existing sessions."
  [& {:keys [name system]}]
  (-> (swap! registry
             (fn [m]
               (if (contains? m name)
                 m
                 (assoc m name (make-session name system)))))
      (get name)))

(defn session-messages
  "Snapshot of the current message vector."
  [session]
  @(:messages session))

(defn reset-session
  "Clear history; re-seeds the system prompt if one was set at creation."
  [session]
  (reset! (:messages session) (initial-messages (:system session)))
  session)

(defn forget-session
  "Drop a session from the registry by name."
  [agent-name]
  (swap! registry dissoc agent-name)
  nil)


;; ---------------------------------------------------------------------------
;; History trimming
;; ---------------------------------------------------------------------------

(defn- trim-history
  "Enforce max-history. Preserves a leading system message, drops oldest
  non-system messages when over the cap."
  [msgs]
  (if (<= (count msgs) max-history)
    msgs
    (let [system? (= "system" (:role (first msgs)))
          keep-n  (if system? (dec max-history) max-history)
          tail    (vec (take-last keep-n (if system? (rest msgs) msgs)))]
      (if system? (into [(first msgs)] tail) tail))))

(defn- append-msg! [session msg]
  (swap! (:messages session) (fn [m] (trim-history (conj m msg)))))


;; ---------------------------------------------------------------------------
;; Tool-call loop helpers
;; ---------------------------------------------------------------------------

(defn- extract-message [resp]
  (get-in resp [:choices 0 :message]))

(defn- extract-finish [resp]
  (get-in resp [:choices 0 :finish_reason]))

(defn- parse-tool-args [args-str]
  (try (json/parse-string args-str true)
       (catch Exception _ {})))

(defn- run-tool-call [tool-impls call]
  (let [fname (get-in call [:function :name])
        args  (parse-tool-args (get-in call [:function :arguments]))
        impl  (get tool-impls fname)
        result (cond
                 (nil? impl) (str "Unknown tool: " fname)
                 :else (try (impl args)
                            (catch Throwable t
                              (str "Tool error: " (.getMessage t)))))]
    (tool-msg (:id call) result)))


;; ---------------------------------------------------------------------------
;; Session-aware chat
;; ---------------------------------------------------------------------------

(defn chat
  "Send user-input through session, return the assistant reply text.

  opts (optional map) is passed through to the API body, minus :tool-impls
  and :max-iters which are client-side only:
    :model        override default model
    :temperature  float
    :max_tokens   int
    :tools        vector of tool-spec maps
    :tool_choice  \"auto\" | \"none\" | {...}
    :tool-impls   {fn-name-string -> (fn [args-map] result)} — client-side
    :max-iters    cap on tool-call iterations (default 6)

  Blocks other chat calls on the same session via a per-session lock."
  ([session user-input] (chat session user-input nil))
  ([session user-input opts]
   (let [lock       (:lock session)
         opts       (or opts {})
         tool-impls (:tool-impls opts)
         max-iters  (or (:max-iters opts) default-tool-iters)
         base-body  (dissoc opts :tool-impls :max-iters)]
     (locking lock
       (append-msg! session (user-msg user-input))
       (loop [iter 0]
         (when (> iter max-iters)
           (throw (ex-info "Tool-call loop exceeded max iterations"
                           {:session (:name session) :max-iters max-iters})))
         (let [resp   (chat-request (assoc base-body :messages @(:messages session)))
               msg    (extract-message resp)
               finish (extract-finish resp)]
           (append-msg! session msg)
           (if (and (= finish "tool_calls") (seq (:tool_calls msg)))
             (do
               (doseq [call (:tool_calls msg)]
                 (append-msg! session (run-tool-call tool-impls call)))
               (recur (inc iter)))
             (:content msg))))))))
