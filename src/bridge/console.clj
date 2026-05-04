(ns bridge.console
  "Simple Swing console for interacting with Motoko.

  Left pane  — conversation transcript (human-readable :response text).
  Right pane — pretty-printed JSON of the last reply envelope.
  Bottom     — input field + Send button. Enter also submits.

  LLM calls run on a background thread so the UI stays responsive."
  (:require [bridge.motoko :as motoko]
            [bridge.llm    :as llm]
            [cheshire.core :as json])
  (:import [javax.swing JFrame JPanel JTextArea JTextField JButton JScrollPane
            SwingUtilities BorderFactory]
           [java.awt BorderLayout Dimension Font]
           [java.awt.event ActionListener]))


;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- pretty-json
  "Pretty-printed JSON string of any Clojure value. Keyword keys become
  strings; namespaced keys keep their namespace (e.g. \"msg/from\")."
  [v]
  (try
    (json/generate-string v {:pretty true})
    (catch Throwable _
      (pr-str v))))

(defn- append!
  "Append S to TEXT-AREA on the EDT and scroll to the bottom."
  [^JTextArea text-area s]
  (SwingUtilities/invokeLater
   (fn []
     (.append text-area s)
     (.setCaretPosition text-area (.. text-area getDocument getLength)))))

(defn- set-text!
  "Replace TEXT-AREA contents with S on the EDT."
  [^JTextArea text-area s]
  (SwingUtilities/invokeLater
   (fn []
     (.setText text-area s)
     (.setCaretPosition text-area 0))))

(defn- reply-text
  "Extract human-readable response from a reply envelope. Falls back to
  pr-str for unexpected shapes."
  [reply]
  (cond
    (nil? reply)            "[no reply]"
    (and (map? reply)
         (:response reply)) (str (:response reply))
    (string? reply)         reply
    :else                   (pr-str reply)))

(defn- agent-label
  "Return a display label for the agent that produced REPLY."
  [reply]
  (let [from (and (map? reply) (:msg/from reply))]
    (cond
      (= from :ghost) "Motoko's ghost"
      (keyword? from) (-> from name clojure.string/capitalize)
      (string? from)  from
      :else           "Motoko")))


;; ---------------------------------------------------------------------------
;; UI construction
;; ---------------------------------------------------------------------------

(defn- mono-font []
  (Font. Font/MONOSPACED Font/PLAIN 12))

(defn- build-text-area
  [^Boolean monospaced?]
  (doto (JTextArea.)
    (.setEditable false)
    (.setLineWrap true)
    (.setWrapStyleWord true)
    (.setFont (if monospaced? (mono-font) (Font. Font/SANS_SERIF Font/PLAIN 13)))
    (.setMargin (java.awt.Insets. 8 8 8 8))))

(defn- build-ui
  "Build the Swing component tree. Returns a map with :frame and the
  components the submit handler needs."
  []
  (let [chat-area  (build-text-area false)
        chat-scroll (doto (JScrollPane. chat-area)
                      (.setBorder (BorderFactory/createTitledBorder "Conversation")))
        input-field (doto (JTextField.)
                      (.setFont (Font. Font/SANS_SERIF Font/PLAIN 13)))
        send-btn    (JButton. "Send")
        bottom      (doto (JPanel. (BorderLayout. 6 6))
                      (.setBorder (BorderFactory/createEmptyBorder 6 6 6 6))
                      (.add input-field BorderLayout/CENTER)
                      (.add send-btn    BorderLayout/EAST))
        root        (doto (JPanel. (BorderLayout.))
                      (.add chat-scroll  BorderLayout/CENTER)
                      (.add bottom BorderLayout/SOUTH))
        frame       (doto (JFrame. "Bridge Console — Section 9")
                      (.setContentPane root)
                      (.setDefaultCloseOperation JFrame/DISPOSE_ON_CLOSE)
                      (.setSize (Dimension. 1100 700))
                      (.setLocationRelativeTo nil))]
    {:frame       frame
     :chat-area   chat-area
     :input-field input-field
     :send-btn    send-btn}))


;; ---------------------------------------------------------------------------
;; Submit handler
;; ---------------------------------------------------------------------------

(defn- submit!
  "Send INPUT-FIELD's text to Motoko and update the UI with the result.
  Runs the LLM call on a background thread."
  [{:keys [^JTextArea chat-area
           ^JTextArea json-area
           ^JTextField input-field
           ^JButton send-btn]}
   api-key]
  (let [text (.trim (.getText input-field))]
    (when-not (empty? text)
      (.setText input-field "")
      (append! chat-area (str "You: " text "\n"))
      ;; Disable input while waiting
      (.setEnabled send-btn false)
      (.setEnabled input-field false)
      (future
        (let [reply (try
                      (binding [llm/*api-key* (or api-key llm/*api-key*)]
                        (motoko/motoko text))
                      (catch Throwable t
                        {:msg/from :motoko
                         :response (str "[Console error: " (.getMessage t) "]")
                         :status   :error
                         :error    {:type :exception
                                    :msg  (.getMessage t)}}))]
          (SwingUtilities/invokeLater
           (fn []
             (append! chat-area (str (agent-label reply) ": " (reply-text reply) "\n\n"))
             (.setEnabled send-btn true)
             (.setEnabled input-field true)
             (.requestFocusInWindow input-field))))))))


;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn start
  "Open the Bridge Console window. Returns the JFrame.

  Optional API-KEY overrides bridge.llm/*api-key* for the duration of each
  Motoko call. When nil, falls back to the existing dynamic var or
  XAI_API_KEY env var."
  ([] (start nil))
  ([api-key]
   (let [result (promise)]
     (SwingUtilities/invokeLater
      (fn []
        (let [{:keys [^JFrame frame
                      ^JTextField input-field
                      ^JButton send-btn] :as ui} (build-ui)
              handler (reify ActionListener
                        (actionPerformed [_ _]
                          (submit! ui api-key)))]
          (.addActionListener send-btn handler)
          (.addActionListener input-field handler)
          (.setVisible frame true)
          (.requestFocusInWindow input-field)
          (deliver result frame))))
     @result)))
