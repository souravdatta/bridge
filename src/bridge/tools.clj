(ns bridge.tools
  "File-system tools for agent use.

  Each public function is a tool that an LLM agent can invoke. The
  docstring of each var is the human/LLM-readable description that callers
  should extract via `(-> #'tool-fn meta :doc)` when building tool specs.

  Security contract
  -----------------
  Every tool accepts a `working-dirs` collection (sequence of absolute path
  strings). The resolved, normalized form of every path argument must
  prefix-match at least one entry in that collection; otherwise the tool
  throws an ex-info with {:type :path-not-allowed}. This prevents an agent
  from reading or writing files outside the project sandbox.

  Logging
  -------
  All tool invocations are automatically logged to stdout with:
  - Tool name
  - Parameters passed
  - Success/Failure status
  - Result or error message
  Format: [TOOL] <name> | <SUCCESS/FAILURE> | params=<map> | result=<value>"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [bridge.diff    :as diff])
  (:import [java.nio.file Path Paths]
           [java.io File]))


;; ---------------------------------------------------------------------------
;; Internal helpers
;; ---------------------------------------------------------------------------

(defn- log-tool
  "Log tool invocation with parameters, result, and status.
  Returns the result unchanged."
  [tool-name params result-or-error success?]
  (let [status (if success? "SUCCESS" "FAILURE")
        log-msg (format "[TOOL] %s | %s | params=%s | result=%s"
                       tool-name
                       status
                       (pr-str params)
                       (if success?
                         (if (string? result-or-error)
                           (str "\"" (subs result-or-error 0 (min 100 (count result-or-error))) 
                                (when (> (count result-or-error) 100) "...") "\"")
                           (pr-str result-or-error))
                         (str "ERROR: " (.getMessage ^Throwable result-or-error))))]
    (println log-msg)
    (when-not success?
      (throw result-or-error))
    result-or-error))

(defmacro with-logging
  "Execute body with logging. Logs tool name, params, and result/error."
  [tool-name params & body]
  `(try
     (let [result# (do ~@body)]
       (log-tool ~tool-name ~params result# true)
       result#)
     (catch Throwable e#
       (log-tool ~tool-name ~params e# false))))

(defn- normalize
  "Return the canonical absolute java.nio.file.Path for a path string,
  resolving `.` and `..` segments without following symlinks."
  ^Path [path-str]
  (-> (Paths/get path-str (into-array String []))
      .toAbsolutePath
      .normalize))

(def ^:dynamic ^:private *current-dir*
  "Current working directory for the active tool call. Bound by make-tool-impls
  closures to the agent's current-dir-atom value before each tool invocation.
  When nil, resolve-path falls back to (first working-dirs)."
  nil)

(defn- resolve-path
  "Resolve PATH relative to the agent's current working directory (*current-dir*)
  when PATH is relative, otherwise return PATH unchanged. Falls back to the
  first working directory when *current-dir* is nil (e.g. in tests that call
  tool functions directly)."
  [working-dirs path]
  (let [p (Paths/get path (into-array String []))]
    (if (.isAbsolute p)
      path
      ;; Relative path — resolve against current-dir (or working-dir root as fallback)
      (str (io/file (or *current-dir* (first working-dirs)) path)))))

(defn- path-allowed?
  "Return true when NORMALIZED-PATH is equal to or nested inside at least
  one of the normalized WORKING-DIRS entries."
  [working-dirs normalized-path]
  (some (fn [wd]
          (let [wd-norm (normalize wd)]
            (.startsWith normalized-path wd-norm)))
        working-dirs))

(defn- assert-allowed!
  "Throw ex-info {:type :path-not-allowed} when PATH is outside every
  WORKING-DIRS entry. Relative paths are resolved against the first
  working directory before validation. Returns the resolved absolute path."
  [working-dirs path]
  (let [resolved-path (resolve-path working-dirs path)
        np (normalize resolved-path)]
    (when-not (path-allowed? working-dirs np)
      (throw (ex-info
              (str "Path \"" path "\" is outside the allowed working directories")
              {:type        :path-not-allowed
               :path        path
               :working-dirs working-dirs})))
    resolved-path))


;; ---------------------------------------------------------------------------
;; Tools
;; ---------------------------------------------------------------------------

(defn read-file
  "Read and return the entire contents of the file at PATH as a UTF-8 string.

  Parameters
    working-dirs – collection of allowed root directories (absolute path strings).
                   PATH must resolve to a location inside one of them.
    path         – absolute or CWD-relative path to the file to read.

  Returns the file contents as a string.
  Throws ex-info {:type :path-not-allowed} when PATH is outside every
  working directory, or {:type :not-a-file} when PATH does not point to a
  regular file."
  [working-dirs path]
  (with-logging "read-file" {:working-dirs working-dirs :path path}
    (let [resolved-path (assert-allowed! working-dirs path)
          f (io/file resolved-path)]
      (when-not (.exists f)
        (throw (ex-info (str "File not found: " path)
                        {:type :not-a-file :path path})))
      (when-not (.isFile f)
        (throw (ex-info (str "Path is not a regular file: " path)
                        {:type :not-a-file :path path})))
      (slurp f))))


(defn list-dir
  "List the immediate children of the directory at PATH.

  Parameters
    working-dirs – collection of allowed root directories (absolute path strings).
                   PATH must resolve to a location inside one of them.
    path         – absolute or CWD-relative path to the directory to list.

  Returns a sequence of maps, one per child entry:
    {:name  \"entry-name\"   ; file or directory name, no parent path
     :type  :file | :dir}  ; :dir for directories, :file for everything else

  Results are sorted by name. Throws ex-info {:type :path-not-allowed} when
  PATH is outside every working directory, or {:type :not-a-dir} when PATH
  does not point to a directory."
  [working-dirs path]
  (with-logging "list-dir" {:working-dirs working-dirs :path path}
    (let [resolved-path (assert-allowed! working-dirs path)
          f (io/file resolved-path)]
      (when-not (.exists f)
        (throw (ex-info (str "Directory not found: " path)
                        {:type :not-a-dir :path path})))
      (when-not (.isDirectory f)
        (throw (ex-info (str "Path is not a directory: " path)
                        {:type :not-a-dir :path path})))
      (->> (.listFiles f)
           (map (fn [^File e]
                  {:name (.getName e)
                   :type (if (.isDirectory e) :dir :file)}))
           (sort-by :name)))))


(defn write-file
  "Write CONTENT to the file at PATH, creating it if it does not yet exist
  and overwriting it if it does. Parent directories must already exist.

  Parameters
    working-dirs – collection of allowed root directories (absolute path strings).
                   PATH must resolve to a location inside one of them.
    path         – absolute or CWD-relative path to the file to write.
    content      – string content to write (UTF-8 encoded).

  Returns nil on success. Throws ex-info {:type :path-not-allowed} when PATH
  is outside every working directory, or {:type :parent-missing} when the
  parent directory of PATH does not exist."
  [working-dirs path content]
  (with-logging "write-file" {:working-dirs working-dirs :path path 
                              :content-length (count content)}
    (let [resolved-path (assert-allowed! working-dirs path)
          f      (io/file resolved-path)
          parent (.getParentFile f)]
      (when (and parent (not (.exists parent)))
        (throw (ex-info (str "Parent directory does not exist: " (.getPath parent))
                        {:type :parent-missing :path path})))
      (spit f content))))


(defn create-dirs
  "Create the directory at PATH together with any missing ancestor directories
  (equivalent to `mkdir -p`). PATH may be an absolute path or a path relative
  to the JVM working directory; it must still resolve to a location inside one
  of the WORKING-DIRS.

  Parameters
    working-dirs – collection of allowed root directories (absolute path strings).
                   PATH must resolve to a location inside one of them.
    path         – relative or absolute path for the directory hierarchy to create.
                   Relative paths are resolved against the JVM current working
                   directory, so in practice callers should prefer passing an
                   absolute path or a path rooted at one of the working dirs.

  Returns true when the directories were created (or already existed),
  false when creation failed. Throws ex-info {:type :path-not-allowed} when
  PATH is outside every working directory."
  [working-dirs path]
  (with-logging "create-dirs" {:working-dirs working-dirs :path path}
    (let [resolved-path (assert-allowed! working-dirs path)]
      (.mkdirs (io/file resolved-path)))))


(defn delete-file
  "Delete the file at PATH.

  Parameters
    working-dirs – collection of allowed root directories (absolute path strings).
                   PATH must resolve to a location inside one of them.
    path         – absolute or relative path to the file to delete.

  Returns true on success. Throws ex-info {:type :path-not-allowed} when PATH
  is outside every working directory, or {:type :not-a-file} when PATH does not
  point to a regular file."
  [working-dirs path]
  (with-logging "delete-file" {:working-dirs working-dirs :path path}
    (let [resolved-path (assert-allowed! working-dirs path)
          f (io/file resolved-path)]
      (when-not (.exists f)
        (throw (ex-info (str "File not found: " path)
                        {:type :not-a-file :path path})))
      (when-not (.isFile f)
        (throw (ex-info (str "Path is not a regular file: " path)
                        {:type :not-a-file :path path})))
      (.delete f))))


(defn delete-dir
  "Delete the directory at PATH and all its contents recursively.
  Equivalent to 'rm -rf PATH'. Use with caution.

  Parameters
    working-dirs – collection of allowed root directories (absolute path strings).
                   PATH must resolve to a location inside one of them.
    path         – absolute or relative path to the directory to delete.

  Returns true on success. Throws ex-info {:type :path-not-allowed} when PATH
  is outside every working directory, or {:type :not-a-dir} when PATH does not
  point to a directory."
  [working-dirs path]
  (with-logging "delete-dir" {:working-dirs working-dirs :path path}
    (let [resolved-path (assert-allowed! working-dirs path)
          f (io/file resolved-path)]
      (when-not (.exists f)
        (throw (ex-info (str "Directory not found: " path)
                        {:type :not-a-dir :path path})))
      (when-not (.isDirectory f)
        (throw (ex-info (str "Path is not a directory: " path)
                        {:type :not-a-dir :path path})))
      ;; Recursive delete helper
      (letfn [(delete-tree [^File file]
                (when (.isDirectory file)
                  (doseq [child (.listFiles file)]
                    (delete-tree child)))
                (.delete file))]
        (delete-tree f)
        true))))


(defn rename-file
  "Rename or move the file from OLD-PATH to NEW-PATH. Both paths must be
  within the allowed working directories.

  Parameters
    working-dirs – collection of allowed root directories (absolute path strings).
                   Both OLD-PATH and NEW-PATH must resolve to locations inside them.
    old-path     – absolute or relative path to the existing file.
    new-path     – absolute or relative path for the new file location/name.

  Returns true on success. Throws ex-info {:type :path-not-allowed} when either
  path is outside every working directory, {:type :not-a-file} when OLD-PATH does
  not point to a regular file, or {:type :rename-failed} when the rename operation
  fails."
  [working-dirs old-path new-path]
  (with-logging "rename-file" {:working-dirs working-dirs :old-path old-path :new-path new-path}
    (let [resolved-old (assert-allowed! working-dirs old-path)
          resolved-new (assert-allowed! working-dirs new-path)
          old-file (io/file resolved-old)
          new-file (io/file resolved-new)]
      (when-not (.exists old-file)
        (throw (ex-info (str "File not found: " old-path)
                        {:type :not-a-file :path old-path})))
      (when-not (.isFile old-file)
        (throw (ex-info (str "Path is not a regular file: " old-path)
                        {:type :not-a-file :path old-path})))
      (when (.exists new-file)
        (throw (ex-info (str "Destination already exists: " new-path)
                        {:type :rename-failed :path new-path})))
      (when-not (.renameTo old-file new-file)
        (throw (ex-info (str "Failed to rename " old-path " to " new-path)
                        {:type :rename-failed :old-path old-path :new-path new-path})))
      true)))


(defn rename-dir
  "Rename or move the directory from OLD-PATH to NEW-PATH. Both paths must be
  within the allowed working directories.

  Parameters
    working-dirs – collection of allowed root directories (absolute path strings).
                   Both OLD-PATH and NEW-PATH must resolve to locations inside them.
    old-path     – absolute or relative path to the existing directory.
    new-path     – absolute or relative path for the new directory location/name.

  Returns true on success. Throws ex-info {:type :path-not-allowed} when either
  path is outside every working directory, {:type :not-a-dir} when OLD-PATH does
  not point to a directory, or {:type :rename-failed} when the rename operation
  fails."
  [working-dirs old-path new-path]
  (with-logging "rename-dir" {:working-dirs working-dirs :old-path old-path :new-path new-path}
    (let [resolved-old (assert-allowed! working-dirs old-path)
          resolved-new (assert-allowed! working-dirs new-path)
          old-file (io/file resolved-old)
          new-file (io/file resolved-new)]
      (when-not (.exists old-file)
        (throw (ex-info (str "Directory not found: " old-path)
                        {:type :not-a-dir :path old-path})))
      (when-not (.isDirectory old-file)
        (throw (ex-info (str "Path is not a directory: " old-path)
                        {:type :not-a-dir :path old-path})))
      (when (.exists new-file)
        (throw (ex-info (str "Destination already exists: " new-path)
                        {:type :rename-failed :path new-path})))
      (when-not (.renameTo old-file new-file)
        (throw (ex-info (str "Failed to rename " old-path " to " new-path)
                        {:type :rename-failed :old-path old-path :new-path new-path})))
      true)))


(defn create-temp-file
  "Create a temporary file in the OS temp directory and return its absolute path.
  Similar to the Unix mktemp utility.

  Parameters
    prefix – prefix string for the temporary file name (optional, default \"tmp\").
    suffix – suffix string for the temporary file name (optional, default \".tmp\").

  Returns the absolute path string of the newly created temporary file in the
  system temp directory (e.g., /tmp on Unix, %TEMP% on Windows).
  The file is created with a unique name but is NOT automatically deleted on JVM exit."
  ([]
   (create-temp-file "tmp" ".tmp"))
  ([prefix]
   (create-temp-file prefix ".tmp"))
  ([prefix suffix]
   (with-logging "create-temp-file" {:prefix prefix :suffix suffix}
     (let [temp-file (File/createTempFile prefix suffix)]
       (.getAbsolutePath temp-file)))))


(defn ask-user
  "Ask the user a question via the console and return their response.
  Prints the question (and optional details) to stdout, then reads one line
  from stdin. Breaks any active ANSI gray tint so the prompt is clearly visible.

  Parameters
    question   – the main question to ask.
    details    – additional context or options (optional). Pass nil or empty
                 string to omit.
    agent-name – name of the asking agent, shown as a label
                 (optional, defaults to \"Agent\").

  Returns the user's text response as a string, or nil on EOF.

  Example
    (ask-user \"What is your name?\" \"Please enter your full name.\" \"Quorra\")"
  ([question]
   (ask-user question nil nil))
  ([question details]
   (ask-user question details nil))
  ([question details agent-name]
   (with-logging "ask-user" {:question question :has-details (some? details) :agent-name agent-name}
     (let [label (str (or agent-name "Agent") " asks")]
       ;; Reset any active gray tint so the prompt is clearly visible
       (print "\033[0m")
       (println)
       (println (str "\033[1m\033[36m" label "\033[0m"))
       (println (str "\033[1m" question "\033[0m"))
       (when (and details (not (str/blank? details)))
         (println (str "\033[90m" details "\033[0m")))
       (print "\033[1myour answer › \033[0m")
       (flush)
       (read-line)))))


;; ---------------------------------------------------------------------------
;; Time / date tools
;; ---------------------------------------------------------------------------

(def ^:private iso-datetime-fmt
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ssXXX"))

(def ^:private iso-date-fmt
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))

(def ^:private weekday-fmt
  (java.time.format.DateTimeFormatter/ofPattern "EEEE"))

(defn- format-zoned [^java.time.ZonedDateTime zdt]
  {:iso     (.format zdt iso-datetime-fmt)
   :date    (.format zdt iso-date-fmt)
   :weekday (.format zdt weekday-fmt)
   :epoch   (.toEpochSecond zdt)})

(defn- format-date [^java.time.LocalDate ld]
  {:date    (.format ld iso-date-fmt)
   :weekday (.format ld weekday-fmt)})

(defn now
  "Return the current local date and time as ISO-8601 with timezone offset,
  plus the epoch second. Always call this tool when the user's request
  depends on the current time; never guess or hard-code a value.

  Returns a map:
    :iso     – \"2026-05-04T14:32:18+05:30\"
    :date    – \"2026-05-04\"
    :weekday – \"Monday\"
    :epoch   – 1746358338"
  []
  (with-logging "now" {}
    (format-zoned (java.time.ZonedDateTime/now))))

(defn today
  "Return today's date as ISO-8601 (YYYY-MM-DD) plus the day of the week.
  Always call this tool when the user's request depends on the current
  date; never guess or hard-code a value.

  Returns a map:
    :date    – \"2026-05-04\"
    :weekday – \"Monday\""
  []
  (with-logging "today" {}
    (format-date (java.time.LocalDate/now))))

(defn time-offset
  "Return the date and time AMOUNT units from now. UNIT must be one of
  \"seconds\", \"minutes\", or \"hours\". AMOUNT may be negative to look
  backward. Use this for questions like \"3 hours from now\" or
  \"45 minutes ago\".

  Parameters
    amount – integer (positive = future, negative = past).
    unit   – one of \"seconds\", \"minutes\", \"hours\".

  Returns a map: {:iso ... :date ... :weekday ... :epoch ...}"
  [amount unit]
  (with-logging "time-offset" {:amount amount :unit unit}
    (let [n   (long amount)
          now (java.time.ZonedDateTime/now)
          zdt (case (str/lower-case (str unit))
                "seconds" (.plusSeconds now n)
                "minutes" (.plusMinutes now n)
                "hours"   (.plusHours   now n)
                (throw (ex-info (str "Unknown time unit: " unit)
                                {:type :bad-unit :unit unit})))]
      (format-zoned zdt))))

(defn date-offset
  "Return the date AMOUNT units from today. UNIT must be one of \"days\",
  \"weeks\", or \"months\". AMOUNT may be negative to look backward.
  Use this for questions like \"a fortnight from now\" (14 days) or
  \"three weeks ago\".

  Parameters
    amount – integer (positive = future, negative = past).
    unit   – one of \"days\", \"weeks\", \"months\".

  Returns a map: {:date ... :weekday ...}"
  [amount unit]
  (with-logging "date-offset" {:amount amount :unit unit}
    (let [n     (long amount)
          today (java.time.LocalDate/now)
          ld    (case (str/lower-case (str unit))
                  "days"   (.plusDays   today n)
                  "weeks"  (.plusWeeks  today n)
                  "months" (.plusMonths today n)
                  (throw (ex-info (str "Unknown date unit: " unit)
                                  {:type :bad-unit :unit unit})))]
      (format-date ld))))


;; ---------------------------------------------------------------------------
;; Tool introspection for LLM
;; ---------------------------------------------------------------------------

(defn- param-schema
  "Build JSON Schema property definition for a parameter name."
  [param-name]
  (case param-name
    working-dirs {:type "array"
                  :items {:type "string"}
                  :description "Collection of allowed root directories (absolute paths)"}
    path         {:type "string"
                  :description "File or directory path (absolute or relative)"}
    old-path     {:type "string"
                  :description "Existing file or directory path (absolute or relative)"}
    new-path     {:type "string"
                  :description "New file or directory path (absolute or relative)"}
    content      {:type "string"
                  :description "String content to write"}
    prefix       {:type "string"
                  :description "Prefix for file name"}
    suffix       {:type "string"
                  :description "Suffix for file name"}
    question     {:type "string"
                  :description "The main question to ask the user"}
    details      {:type "string"
                  :description "Additional context, instructions, or options (optional)"}
    amount       {:type "integer"
                  :description "Number of units to offset (negative for past, positive for future)"}
    unit         {:type "string"
                  :description "Unit of offset. Time: seconds|minutes|hours. Date: days|weeks|months."}
    ;; default
    {:type "string"}))

(defn- build-params-schema
  "Build JSON Schema for function parameters from a list of arglists.
  All parameters across all arities are listed in :properties, but only
  those present in EVERY arity are marked :required (so optional params
  in shorter arities remain optional)."
  [arglists]
  (let [all-params (->> arglists (mapcat identity) distinct vec)
        ;; Required = intersection of params across all arities
        required-params (reduce (fn [acc al] (filter (set al) acc))
                                all-params
                                arglists)
        properties (into {} (map (fn [p] [(name p) (param-schema p)]) all-params))]
    {:type "object"
     :properties properties
     :required (mapv name required-params)}))

(defn- fn-to-tool-spec
  "Convert a function var to xAI tool specification format."
  [fn-var]
  (let [m (meta fn-var)
        fn-name (name (:name m))
        doc (or (:doc m) "")
        arglists (:arglists m)
        params (build-params-schema arglists)]
    {:type "function"
     :function {:name fn-name
                :description doc
                :parameters params}}))

(def ^:private cwd-tool-specs
  "xAI tool specs for tools whose implementations live inside make-tool-impls
  (they capture per-instance state such as current-dir-atom or agent-name and
  therefore cannot be auto-generated from public vars)."
  [{:type "function"
    :function {:name        "write-file-with-diff"
               :description "Write CONTENT to the file at PATH after showing a coloured diff preview and prompting for confirmation. Creates the file if it does not exist (all lines shown as additions). Returns \"Written: <path>\" on success. Throws if the user declines or the parent directory is missing. Always prefer this over write-file when creating or editing files."
               :parameters  {:type       "object"
                             :properties {"path"    {:type        "string"
                                                     :description "File path (absolute or relative to the current working directory)."}
                                          "content" {:type        "string"
                                                     :description "Full new content for the file (UTF-8 string)."}}
                             :required   ["path" "content"]}}}
   {:type "function"
    :function {:name        "change-dir"
               :description "Change the current working directory to PATH. PATH may be relative (resolved from the current working directory) or absolute. The destination must be a directory within the agent's root working directory. Returns the new absolute CWD path."
               :parameters  {:type       "object"
                             :properties {"path" {:type        "string"
                                                  :description "Directory path to change into. Relative paths resolve from the current working directory."}}
                             :required   ["path"]}}}
   {:type "function"
    :function {:name        "pwd"
               :description "Return the agent's current working directory as an absolute path string. Call this when you need to know where relative paths will resolve to."
               :parameters  {:type       "object"
                             :properties {}}}}])

(defn tools-def
  "Return a vector of xAI-compatible tool specifications for all public
  tool functions in this namespace. Automatically discovers all public
  functions (except this one) and extracts their docstrings and parameter
  schemas. Also includes change-dir and pwd.

  Returns a vector suitable for passing as :tools to bridge.llm/chat."
  []
  (let [ns-publics (ns-publics 'bridge.tools)]
    (vec
      (concat
        (->> ns-publics
             vals
             (filter (fn [v]
                       (and (fn? @v)
                            (not (#{'tools-def 'make-tool-impls 'format-tools-list} (:name (meta v)))))))
             (map fn-to-tool-spec))
        cwd-tool-specs))))

(defn format-tools-list
  "Return a human-readable bullet list of all available tools, one per line.
  Each line is formatted as \"- <name>: <first-paragraph-of-docstring>\".
  Intended for embedding directly in agent system prompts."
  []
  (str/join "\n"
            (for [tool (tools-def)]
              (let [fn-name (get-in tool [:function :name])
                    desc    (get-in tool [:function :description])
                    summary (first (str/split desc #"\n\n"))]
                (str "- " fn-name ": " (str/trim summary))))))

(defn make-tool-impls
  "Build a map of tool implementation functions for use with bridge.llm/chat.

  Each tool function in this namespace that accepts a working-dirs parameter
  is wrapped with the provided WORKING-DIRS value. The ask-user tool is
  wrapped with AGENT-NAME so the dialog title identifies which agent is
  asking. Tools without working-dirs (like create-temp-file) are wrapped
  as-is.

  Parameters
    working-dirs – collection of allowed root directories (absolute path strings)
                   to pass to each tool implementation.
    agent-name   – name of the agent (e.g. \"Quorra\"), used as the title of
                   the ask-user dialog so the user knows who is asking.

  Returns a map of {\"tool-name\" (fn [args-map] result)} suitable for passing
  as :tool-impls to bridge.llm/chat."
  [working-dirs agent-name]
  (let [current-dir-atom (atom (str (first working-dirs)))]
    {"read-file"        (fn [args]
                          (binding [*current-dir* @current-dir-atom]
                            (read-file working-dirs (:path args))))
     "list-dir"         (fn [args]
                          (binding [*current-dir* @current-dir-atom]
                            (list-dir working-dirs (:path args))))
     "write-file"       (fn [args]
                          (binding [*current-dir* @current-dir-atom]
                            (write-file working-dirs (:path args) (:content args))))
     "create-dirs"      (fn [args]
                          (binding [*current-dir* @current-dir-atom]
                            (create-dirs working-dirs (:path args))))
     "delete-file"      (fn [args]
                          (binding [*current-dir* @current-dir-atom]
                            (delete-file working-dirs (:path args))))
     "delete-dir"       (fn [args]
                          (binding [*current-dir* @current-dir-atom]
                            (delete-dir working-dirs (:path args))))
     "rename-file"      (fn [args]
                          (binding [*current-dir* @current-dir-atom]
                            (rename-file working-dirs (:old-path args) (:new-path args))))
     "rename-dir"       (fn [args]
                          (binding [*current-dir* @current-dir-atom]
                            (rename-dir working-dirs (:old-path args) (:new-path args))))
     "create-temp-file" (fn [args]
                          (if (and (:prefix args) (:suffix args))
                            (create-temp-file (:prefix args) (:suffix args))
                            (if (:prefix args)
                              (create-temp-file (:prefix args))
                              (create-temp-file))))
     "ask-user"         (fn [args] (ask-user (:question args) (:details args) agent-name))
     "now"              (fn [_]    (now))
     "today"            (fn [_]    (today))
     "time-offset"      (fn [args] (time-offset (:amount args) (:unit args)))
     "date-offset"      (fn [args] (date-offset (:amount args) (:unit args)))
     "write-file-with-diff"
                        (fn [args]
                          (with-logging "write-file-with-diff" (dissoc args :content)
                            (let [path     (:path args)
                                  content  (:content args)
                                  resolved (binding [*current-dir* @current-dir-atom]
                                             (assert-allowed! working-dirs path))
                                  ^File f  (io/file resolved)
                                  old-text (if (.exists f) (slurp f) "")
                                  diff-vec (diff/compute-diff old-text content)]
                              (println (diff/render-diff diff-vec path))
                              (if (every? #(= :eq (first %)) diff-vec)
                                "No changes — file not written."
                                (let [answer (ask-user "Apply these changes?"
                                                       "Type \"yes\" to write the file."
                                                       agent-name)]
                                  (if (= "yes" (str/lower-case (str/trim (or answer ""))))
                                    (do
                                      (binding [*current-dir* @current-dir-atom]
                                        (write-file working-dirs path content))
                                      (str "Written: " resolved))
                                    (throw (ex-info "Write cancelled by user."
                                                    {:type :cancelled :path path}))))))))
     "change-dir"       (fn [args]
                          (with-logging "change-dir" args
                            (let [path     (:path args)
                                  resolved (binding [*current-dir* @current-dir-atom]
                                             (assert-allowed! working-dirs path))
                                  ^File f  (io/file resolved)]
                              (when-not (.isDirectory f)
                                (throw (ex-info (str "Not a directory: " path)
                                                {:type :not-a-dir :path path})))
                              (reset! current-dir-atom resolved)
                              (str "CWD: " resolved))))
     "pwd"              (fn [_]
                          (with-logging "pwd" {}
                            @current-dir-atom))}))