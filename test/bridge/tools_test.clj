(ns bridge.tools-test
  "Tests for bridge.tools – both functional behaviour and documentation
  completeness (each tool must carry an LLM-extractable docstring)."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [bridge.tools :as tools]
            [clojure.java.io :as io])
  (:import [java.io File]))


;; ---------------------------------------------------------------------------
;; Test-fixture helpers
;; ---------------------------------------------------------------------------

(defn- create-tmp-dir!
  "Create a fresh temporary directory and return it as a java.io.File."
  []
  (let [f (File/createTempFile "bridge-tools-test" "")]
    (.delete f)
    (.mkdirs f)
    f))

(defn- delete-tree!
  "Recursively delete FILE and all of its children."
  [^File f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-tree! child)))
  (.delete f))

(defmacro with-tmp-dir
  "Bind SYM to a fresh temp directory for the duration of BODY, deleting
  it (and all contents) on exit."
  [sym & body]
  `(let [~sym (create-tmp-dir!)]
     (try
       ~@body
       (finally
         (delete-tree! ~sym)))))


;; ---------------------------------------------------------------------------
;; Documentation tests – every public tool must have a docstring
;; ---------------------------------------------------------------------------

(def ^:private all-tool-vars
  "The complete set of public tool vars that must carry docstrings."
  [#'tools/read-file
   #'tools/list-dir
   #'tools/write-file
   #'tools/create-dirs
   #'tools/delete-file
   #'tools/delete-dir
   #'tools/rename-file
   #'tools/rename-dir
   #'tools/create-temp-file])

(deftest tools-have-docstrings
  (doseq [v all-tool-vars]
    (let [doc (:doc (meta v))
          name (str (:name (meta v)))]
      (testing (str name " has a non-blank docstring")
        (is (string? doc)
            (str name " is missing a :doc entry in its metadata"))
        (is (not (str/blank? doc))
            (str name " has a blank docstring"))))))

(deftest tool-docstrings-describe-working-dirs-param
  "Every tool docstring must mention the working-dirs security contract,
  except for create-temp-file and ask-user which don't use working directories."
  (doseq [v all-tool-vars
          :when (not (contains? #{#'tools/create-temp-file #'tools/ask-user} v))]
    (let [doc (:doc (meta v))
          name (str (:name (meta v)))]
      (testing (str name " docstring references working-dirs")
        (is (str/includes? (str/lower-case doc) "working-dirs")
            (str name " docstring does not mention working-dirs"))))))

(deftest tool-docstrings-extractable-via-meta
  "Smoke test: :doc can be pulled via the standard (-> var meta :doc) pattern."
  (doseq [v all-tool-vars]
    (is (some? (-> v meta :doc))
        (str "Could not extract :doc from " (:name (meta v))))))


;; ---------------------------------------------------------------------------
;; read-file
;; ---------------------------------------------------------------------------

(deftest read-file-returns-content
  (with-tmp-dir d
    (let [f    (io/file d "hello.txt")
          wd   [(.getAbsolutePath d)]]
      (spit f "Hello, world!")
      (is (= "Hello, world!" (tools/read-file wd (.getAbsolutePath f)))))))

(deftest read-file-multiline
  (with-tmp-dir d
    (let [f  (io/file d "multi.txt")
          wd [(.getAbsolutePath d)]]
      (spit f "line1\nline2\nline3")
      (is (= "line1\nline2\nline3"
             (tools/read-file wd (.getAbsolutePath f)))))))

(deftest read-file-rejects-path-outside-working-dirs
  (with-tmp-dir d
    (let [wd [(.getAbsolutePath d)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/read-file wd (System/getProperty "java.io.tmpdir")))))))

(deftest read-file-throws-on-missing-file
  (with-tmp-dir d
    (let [wd [(.getAbsolutePath d)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (tools/read-file wd (.getAbsolutePath (io/file d "no-such.txt"))))))))

(deftest read-file-throws-on-directory
  (with-tmp-dir d
    (let [sub (io/file d "subdir")
          wd  [(.getAbsolutePath d)]]
      (.mkdir sub)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not a regular file"
           (tools/read-file wd (.getAbsolutePath sub)))))))

(deftest read-file-path-traversal-blocked
  "Ensure ../ escapes are blocked."
  (with-tmp-dir d
    (let [sub (io/file d "sub")
          _   (.mkdir sub)
          ;; try to escape one level above the allowed dir
          evil (str (.getAbsolutePath sub) File/separator ".." File/separator ".." File/separator "escape.txt")
          wd   [(.getAbsolutePath sub)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/read-file wd evil))))))


;; ---------------------------------------------------------------------------
;; list-dir
;; ---------------------------------------------------------------------------

(deftest list-dir-returns-entries
  (with-tmp-dir d
    (spit (io/file d "a.txt") "a")
    (spit (io/file d "b.txt") "b")
    (.mkdir (io/file d "mydir"))
    (let [wd      [(.getAbsolutePath d)]
          entries (tools/list-dir wd (.getAbsolutePath d))]
      (is (= #{"a.txt" "b.txt" "mydir"}
             (set (map :name entries)))))))

(deftest list-dir-types
  (with-tmp-dir d
    (spit (io/file d "file.txt") "x")
    (.mkdir (io/file d "dir"))
    (let [wd      [(.getAbsolutePath d)]
          entries (into {} (map (juxt :name :type)
                                (tools/list-dir wd (.getAbsolutePath d))))]
      (is (= :file (entries "file.txt")))
      (is (= :dir  (entries "dir"))))))

(deftest list-dir-results-sorted
  (with-tmp-dir d
    (spit (io/file d "z.txt") "")
    (spit (io/file d "a.txt") "")
    (spit (io/file d "m.txt") "")
    (let [wd    [(.getAbsolutePath d)]
          names (map :name (tools/list-dir wd (.getAbsolutePath d)))]
      (is (= (sort names) names)))))

(deftest list-dir-empty-directory
  (with-tmp-dir d
    (let [sub (io/file d "empty")]
      (.mkdir sub)
      (is (empty? (tools/list-dir [(.getAbsolutePath d)]
                                  (.getAbsolutePath sub)))))))

(deftest list-dir-rejects-path-outside-working-dirs
  (with-tmp-dir d
    (let [wd [(.getAbsolutePath d)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/list-dir wd (System/getProperty "java.io.tmpdir")))))))

(deftest list-dir-throws-on-missing-dir
  (with-tmp-dir d
    (let [wd [(.getAbsolutePath d)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (tools/list-dir wd (.getAbsolutePath (io/file d "no-such"))))))))

(deftest list-dir-throws-on-file
  (with-tmp-dir d
    (let [f  (io/file d "f.txt")
          wd [(.getAbsolutePath d)]]
      (spit f "x")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not a directory"
           (tools/list-dir wd (.getAbsolutePath f)))))))

(deftest list-dir-multiple-working-dirs
  "Path in a second working dir is accepted."
  (with-tmp-dir d1
    (with-tmp-dir d2
      (spit (io/file d2 "x.txt") "")
      (let [wd [(.getAbsolutePath d1) (.getAbsolutePath d2)]]
        (is (= [{:name "x.txt" :type :file}]
               (tools/list-dir wd (.getAbsolutePath d2))))))))


;; ---------------------------------------------------------------------------
;; write-file
;; ---------------------------------------------------------------------------

(deftest write-file-creates-new-file
  (with-tmp-dir d
    (let [f  (io/file d "new.txt")
          wd [(.getAbsolutePath d)]]
      (tools/write-file wd (.getAbsolutePath f) "created")
      (is (.exists f))
      (is (= "created" (slurp f))))))

(deftest write-file-overwrites-existing-file
  (with-tmp-dir d
    (let [f  (io/file d "existing.txt")
          wd [(.getAbsolutePath d)]]
      (spit f "old content")
      (tools/write-file wd (.getAbsolutePath f) "new content")
      (is (= "new content" (slurp f))))))

(deftest write-file-rejects-path-outside-working-dirs
  (with-tmp-dir d
    (let [wd [(.getAbsolutePath d)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/write-file wd
                             (str (System/getProperty "java.io.tmpdir")
                                  File/separator "evil.txt")
                             "bad"))))))

(deftest write-file-throws-when-parent-missing
  (with-tmp-dir d
    (let [f  (io/file d "nonexistent" "file.txt")
          wd [(.getAbsolutePath d)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Parent directory does not exist"
           (tools/write-file wd (.getAbsolutePath f) "content"))))))

(deftest write-file-returns-nil
  (with-tmp-dir d
    (let [f  (io/file d "r.txt")
          wd [(.getAbsolutePath d)]]
      (is (nil? (tools/write-file wd (.getAbsolutePath f) "x"))))))


;; ---------------------------------------------------------------------------
;; create-dirs
;; ---------------------------------------------------------------------------

(deftest create-dirs-single-level
  (with-tmp-dir d
    (let [new-dir (io/file d "mydir")
          wd      [(.getAbsolutePath d)]]
      (tools/create-dirs wd (.getAbsolutePath new-dir))
      (is (.exists new-dir))
      (is (.isDirectory new-dir)))))

(deftest create-dirs-nested-hierarchy
  (with-tmp-dir d
    (let [nested (io/file d "a" "b" "c")
          wd     [(.getAbsolutePath d)]]
      (tools/create-dirs wd (.getAbsolutePath nested))
      (is (.exists nested))
      (is (.isDirectory nested)))))

(deftest create-dirs-idempotent
  "Calling create-dirs on an existing path does not throw."
  (with-tmp-dir d
    (let [sub (io/file d "existing")
          wd  [(.getAbsolutePath d)]]
      (.mkdir sub)
      (is (some? (tools/create-dirs wd (.getAbsolutePath sub)))))))

(deftest create-dirs-rejects-path-outside-working-dirs
  (with-tmp-dir d
    (let [wd [(.getAbsolutePath d)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/create-dirs wd
                              (str (System/getProperty "java.io.tmpdir")
                                   File/separator "evil-dir")))))))

(deftest create-dirs-path-traversal-blocked
  (with-tmp-dir d
    (let [sub  (io/file d "sub")
          _    (.mkdir sub)
          evil (str (.getAbsolutePath sub) File/separator ".." File/separator ".." File/separator "escape")
          wd   [(.getAbsolutePath sub)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/create-dirs wd evil))))))


;; ---------------------------------------------------------------------------
;; delete-file
;; ---------------------------------------------------------------------------

(deftest delete-file-removes-file
  (with-tmp-dir tmp
    (let [f (io/file tmp "target.txt")
          _ (spit f "content")
          wd [(.getPath tmp)]]
      (is (.exists f))
      (tools/delete-file wd (.getPath f))
      (is (not (.exists f))))))

(deftest delete-file-with-relative-path
  (with-tmp-dir tmp
    (spit (io/file tmp "file.txt") "data")
    (let [wd [(.getPath tmp)]]
      (tools/delete-file wd "file.txt")
      (is (not (.exists (io/file tmp "file.txt")))))))

(deftest delete-file-rejects-directory
  (with-tmp-dir tmp
    (let [dir (io/file tmp "mydir")
          _   (.mkdir dir)
          wd  [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not a regular file"
           (tools/delete-file wd (.getPath dir)))))))

(deftest delete-file-throws-on-missing
  (with-tmp-dir tmp
    (let [wd [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"File not found"
           (tools/delete-file wd (str (.getPath tmp) File/separator "nosuch.txt")))))))

(deftest delete-file-rejects-path-outside-working-dirs
  (with-tmp-dir tmp
    (let [wd [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/delete-file wd (str (System/getProperty "java.io.tmpdir") File/separator "evil.txt")))))))


;; ---------------------------------------------------------------------------
;; delete-dir
;; ---------------------------------------------------------------------------

(deftest delete-dir-removes-empty-directory
  (with-tmp-dir tmp
    (let [dir (io/file tmp "emptydir")
          _   (.mkdir dir)
          wd  [(.getPath tmp)]]
      (is (.exists dir))
      (tools/delete-dir wd (.getPath dir))
      (is (not (.exists dir))))))

(deftest delete-dir-removes-directory-with-contents
  (with-tmp-dir tmp
    (let [dir  (io/file tmp "parent")
          sub  (io/file dir "subdir")
          f1   (io/file dir "file1.txt")
          f2   (io/file sub "file2.txt")
          _    (.mkdirs sub)
          _    (spit f1 "content1")
          _    (spit f2 "content2")
          wd   [(.getPath tmp)]]
      (is (.exists dir))
      (is (.exists f1))
      (is (.exists f2))
      (tools/delete-dir wd (.getPath dir))
      (is (not (.exists dir)))
      (is (not (.exists f1)))
      (is (not (.exists f2))))))

(deftest delete-dir-with-relative-path
  (with-tmp-dir tmp
    (let [dir (io/file tmp "testdir")
          _   (.mkdir dir)
          wd  [(.getPath tmp)]]
      (tools/delete-dir wd "testdir")
      (is (not (.exists dir))))))

(deftest delete-dir-rejects-file
  (with-tmp-dir tmp
    (let [f  (io/file tmp "file.txt")
          _  (spit f "data")
          wd [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not a directory"
           (tools/delete-dir wd (.getPath f)))))))

(deftest delete-dir-throws-on-missing
  (with-tmp-dir tmp
    (let [wd [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Directory not found"
           (tools/delete-dir wd (str (.getPath tmp) File/separator "nosuchdir")))))))

(deftest delete-dir-rejects-path-outside-working-dirs
  (with-tmp-dir tmp
    (let [wd [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/delete-dir wd (System/getProperty "java.io.tmpdir")))))))


;; ---------------------------------------------------------------------------
;; rename-file
;; ---------------------------------------------------------------------------

(deftest rename-file-renames-file
  (with-tmp-dir tmp
    (let [old-f (io/file tmp "old.txt")
          new-f (io/file tmp "new.txt")
          _     (spit old-f "content")
          wd    [(.getPath tmp)]]
      (is (.exists old-f))
      (is (not (.exists new-f)))
      (tools/rename-file wd (.getPath old-f) (.getPath new-f))
      (is (not (.exists old-f)))
      (is (.exists new-f))
      (is (= "content" (slurp new-f))))))

(deftest rename-file-with-relative-paths
  (with-tmp-dir tmp
    (spit (io/file tmp "source.txt") "data")
    (let [wd [(.getPath tmp)]]
      (tools/rename-file wd "source.txt" "dest.txt")
      (is (not (.exists (io/file tmp "source.txt"))))
      (is (.exists (io/file tmp "dest.txt")))
      (is (= "data" (slurp (io/file tmp "dest.txt")))))))

(deftest rename-file-to-subdirectory
  (with-tmp-dir tmp
    (let [sub (io/file tmp "subdir")
          _   (.mkdir sub)
          f   (io/file tmp "file.txt")
          _   (spit f "data")
          wd  [(.getPath tmp)]]
      (tools/rename-file wd (.getPath f) (str (.getPath sub) File/separator "moved.txt"))
      (is (not (.exists f)))
      (is (.exists (io/file sub "moved.txt"))))))

(deftest rename-file-throws-on-missing-source
  (with-tmp-dir tmp
    (let [wd [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"File not found"
           (tools/rename-file wd "nosuch.txt" "new.txt"))))))

(deftest rename-file-throws-on-existing-destination
  (with-tmp-dir tmp
    (spit (io/file tmp "file1.txt") "data1")
    (spit (io/file tmp "file2.txt") "data2")
    (let [wd [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already exists"
           (tools/rename-file wd "file1.txt" "file2.txt"))))))

(deftest rename-file-rejects-directory
  (with-tmp-dir tmp
    (let [dir (io/file tmp "dir")
          _   (.mkdir dir)
          wd  [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not a regular file"
           (tools/rename-file wd (.getPath dir) "newname"))))))

(deftest rename-file-validates-both-paths
  (with-tmp-dir tmp
    (spit (io/file tmp "file.txt") "data")
    (let [wd [(.getPath tmp)]
          evil-path (str (System/getProperty "java.io.tmpdir") File/separator "evil.txt")]
      ;; Old path outside working-dirs
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/rename-file wd evil-path "new.txt")))
      ;; New path outside working-dirs
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/rename-file wd "file.txt" evil-path))))))


;; ---------------------------------------------------------------------------
;; rename-dir
;; ---------------------------------------------------------------------------

(deftest rename-dir-renames-directory
  (with-tmp-dir tmp
    (let [old-dir (io/file tmp "olddir")
          new-dir (io/file tmp "newdir")
          _       (.mkdir old-dir)
          _       (spit (io/file old-dir "file.txt") "content")
          wd      [(.getPath tmp)]]
      (is (.exists old-dir))
      (is (not (.exists new-dir)))
      (tools/rename-dir wd (.getPath old-dir) (.getPath new-dir))
      (is (not (.exists old-dir)))
      (is (.exists new-dir))
      (is (.exists (io/file new-dir "file.txt")))
      (is (= "content" (slurp (io/file new-dir "file.txt")))))))

(deftest rename-dir-with-relative-paths
  (with-tmp-dir tmp
    (let [old-dir (io/file tmp "source")
          _       (.mkdir old-dir)
          wd      [(.getPath tmp)]]
      (tools/rename-dir wd "source" "target")
      (is (not (.exists old-dir)))
      (is (.exists (io/file tmp "target"))))))

(deftest rename-dir-throws-on-missing-source
  (with-tmp-dir tmp
    (let [wd [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Directory not found"
           (tools/rename-dir wd "nosuchdir" "newdir"))))))

(deftest rename-dir-throws-on-existing-destination
  (with-tmp-dir tmp
    (let [dir1 (io/file tmp "dir1")
          dir2 (io/file tmp "dir2")
          _    (.mkdir dir1)
          _    (.mkdir dir2)
          wd   [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"already exists"
           (tools/rename-dir wd "dir1" "dir2"))))))

(deftest rename-dir-rejects-file
  (with-tmp-dir tmp
    (spit (io/file tmp "file.txt") "data")
    (let [wd [(.getPath tmp)]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not a directory"
           (tools/rename-dir wd "file.txt" "newname"))))))

(deftest rename-dir-validates-both-paths
  (with-tmp-dir tmp
    (let [dir (io/file tmp "dir")
          _   (.mkdir dir)
          wd  [(.getPath tmp)]
          evil-path (str (System/getProperty "java.io.tmpdir") File/separator "evildir")]
      ;; Old path outside working-dirs
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/rename-dir wd evil-path "newdir")))
      ;; New path outside working-dirs
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside the allowed working directories"
           (tools/rename-dir wd "dir" evil-path))))))


;; ---------------------------------------------------------------------------
;; create-temp-file
;; ---------------------------------------------------------------------------

(deftest create-temp-file-basic
  (let [tmp-path (tools/create-temp-file)]
    (is (string? tmp-path))
    (is (.exists (io/file tmp-path)))
    ;; Temp file should be in system temp directory
    (is (.startsWith tmp-path (System/getProperty "java.io.tmpdir")))))

(deftest create-temp-file-with-prefix
  (let [tmp-path (tools/create-temp-file "myprefix")]
    (is (.contains (.getName (io/file tmp-path)) "myprefix"))))

(deftest create-temp-file-with-prefix-and-suffix
  (let [tmp-path (tools/create-temp-file "test" ".log")]
    (is (.endsWith tmp-path ".log"))
    (is (.contains (.getName (io/file tmp-path)) "test"))))

(deftest create-temp-file-unique-names
  "Multiple calls produce different file names."
  (let [p1 (tools/create-temp-file)
        p2 (tools/create-temp-file)]
    (is (not= p1 p2))))


;; ---------------------------------------------------------------------------
;; tools-def - LLM tool introspection
;; ---------------------------------------------------------------------------

(deftest tools-def-returns-vector
  (let [defs (tools/tools-def)]
    (is (vector? defs))
    (is (pos? (count defs)))))

(deftest tools-def-has-correct-structure
  "Each tool spec has the xAI-required structure."
  (let [defs (tools/tools-def)]
    (doseq [spec defs]
      (is (= "function" (:type spec)))
      (is (map? (:function spec)))
      (is (string? (get-in spec [:function :name])))
      (is (string? (get-in spec [:function :description])))
      (is (map? (get-in spec [:function :parameters])))
      (is (= "object" (get-in spec [:function :parameters :type]))))))

(deftest tools-def-includes-all-tools
  "All public tool functions should be in the result."
  (let [defs (tools/tools-def)
        names (set (map #(get-in % [:function :name]) defs))]
    (is (contains? names "read-file"))
    (is (contains? names "list-dir"))
    (is (contains? names "write-file"))
    (is (contains? names "create-dirs"))
    (is (contains? names "delete-file"))
    (is (contains? names "delete-dir"))
    (is (contains? names "rename-file"))
    (is (contains? names "rename-dir"))
    (is (contains? names "create-temp-file"))
    ;; ask-user is also present but not tested due to interactive UI
    (is (contains? names "ask-user"))
    (is (contains? names "now"))
    (is (contains? names "today"))
    (is (contains? names "time-offset"))
    (is (contains? names "date-offset"))))

(deftest tools-def-excludes-itself
  "tools-def should not include itself in the results."
  (let [defs (tools/tools-def)
        names (set (map #(get-in % [:function :name]) defs))]
    (is (not (contains? names "tools-def")))))

(deftest tools-def-extracts-docstrings
  "Each tool should have its docstring as description."
  (let [defs (tools/tools-def)
        read-file-spec (first (filter #(= "read-file" (get-in % [:function :name])) defs))]
    (is (some? read-file-spec))
    (let [desc (get-in read-file-spec [:function :description])]
      (is (string? desc))
      (is (not (clojure.string/blank? desc)))
      (is (.contains desc "Read and return")))))

(deftest tools-def-has-parameters
  "Each tool should have parameters defined."
  (let [defs (tools/tools-def)
        write-file-spec (first (filter #(= "write-file" (get-in % [:function :name])) defs))]
    (is (some? write-file-spec))
    (let [params (get-in write-file-spec [:function :parameters])]
      (is (map? (:properties params)))
      (is (vector? (:required params)))
      (is (contains? (:properties params) "working-dirs"))
      (is (contains? (:properties params) "path"))
      (is (contains? (:properties params) "content")))))


;; ---------------------------------------------------------------------------
;; Relative path tests
;; ---------------------------------------------------------------------------

(deftest write-file-accepts-relative-path
  "Relative paths should be resolved against the first working directory."
  (with-tmp-dir tmp
    (let [wd [(.getPath tmp)]]
      (tools/write-file wd "relative.txt" "content")
      (is (.exists (io/file tmp "relative.txt")))
      (is (= "content" (slurp (io/file tmp "relative.txt")))))))

(deftest read-file-accepts-relative-path
  "Relative paths should be resolved against the first working directory."
  (with-tmp-dir tmp
    (spit (io/file tmp "data.txt") "test data")
    (let [wd [(.getPath tmp)]
          content (tools/read-file wd "data.txt")]
      (is (= "test data" content)))))

(deftest list-dir-accepts-relative-path
  "Relative paths should be resolved against the first working directory."
  (with-tmp-dir tmp
    (spit (io/file tmp "file.txt") "x")
    (.mkdirs (io/file tmp "subdir"))
    (let [wd [(.getPath tmp)]
          entries (tools/list-dir wd "subdir")]
      (is (= [] entries)))))

(deftest create-dirs-accepts-relative-path
  "Relative paths should be resolved against the first working directory."
  (with-tmp-dir tmp
    (let [wd [(.getPath tmp)]]
      (tools/create-dirs wd "nested/path/here")
      (is (.exists (io/file tmp "nested" "path" "here")))
      (is (.isDirectory (io/file tmp "nested" "path" "here"))))))


;; ---------------------------------------------------------------------------
;; Time / date tools
;; ---------------------------------------------------------------------------

(def ^:private iso-datetime-re
  #"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+\-]\d{2}:\d{2}$")

(def ^:private iso-date-re
  #"^\d{4}-\d{2}-\d{2}$")

(def ^:private weekdays
  #{"Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday" "Sunday"})

(deftest now-returns-iso-datetime-and-epoch
  (let [r (tools/now)]
    (is (re-matches iso-datetime-re (:iso r)))
    (is (re-matches iso-date-re     (:date r)))
    (is (contains? weekdays         (:weekday r)))
    (is (integer? (:epoch r)))
    (is (pos? (:epoch r)))))

(deftest now-epoch-near-system-time
  (let [r       (tools/now)
        wall    (quot (System/currentTimeMillis) 1000)
        delta   (Math/abs (- wall (long (:epoch r))))]
    (is (< delta 5) (str "epoch off by " delta " seconds"))))

(deftest today-returns-date-and-weekday
  (let [r (tools/today)]
    (is (re-matches iso-date-re (:date r)))
    (is (contains? weekdays     (:weekday r)))))

(deftest time-offset-future-and-past
  (let [base    (tools/now)
        future  (tools/time-offset 60 "minutes")
        past    (tools/time-offset -1 "hours")]
    (is (re-matches iso-datetime-re (:iso future)))
    (is (re-matches iso-datetime-re (:iso past)))
    (is (> (:epoch future) (:epoch base)))
    (is (< (:epoch past)   (:epoch base)))
    (is (<= 3540 (- (:epoch future) (:epoch base)) 3660))
    (is (<= 3540 (- (:epoch base)   (:epoch past))  3660))))

(deftest time-offset-supports-all-units
  (doseq [u ["seconds" "minutes" "hours" "SECONDS" "Hours"]]
    (is (re-matches iso-datetime-re (:iso (tools/time-offset 1 u))))))

(deftest time-offset-rejects-unknown-unit
  (is (thrown? clojure.lang.ExceptionInfo
               (tools/time-offset 1 "centuries"))))

(deftest date-offset-future-and-past
  (let [today  (tools/today)
        ahead  (tools/date-offset 14 "days")
        behind (tools/date-offset -3 "weeks")
        td     (java.time.LocalDate/parse (:date today))
        ad     (java.time.LocalDate/parse (:date ahead))
        bd     (java.time.LocalDate/parse (:date behind))]
    (is (re-matches iso-date-re (:date ahead)))
    (is (re-matches iso-date-re (:date behind)))
    (is (= 14  (.until td ad java.time.temporal.ChronoUnit/DAYS)))
    (is (= -21 (.until td bd java.time.temporal.ChronoUnit/DAYS)))
    (is (contains? weekdays (:weekday ahead)))
    (is (contains? weekdays (:weekday behind)))))

(deftest date-offset-supports-all-units
  (doseq [u ["days" "weeks" "months" "DAYS" "Months"]]
    (is (re-matches iso-date-re (:date (tools/date-offset 1 u))))))

(deftest date-offset-rejects-unknown-unit
  (is (thrown? clojure.lang.ExceptionInfo
               (tools/date-offset 1 "decades"))))

(deftest time-and-date-tools-have-no-required-params
  "now and today take no params; their JSON Schema must reflect that."
  (let [defs (tools/tools-def)
        spec-of (fn [n] (first (filter #(= n (get-in % [:function :name])) defs)))]
    (doseq [n ["now" "today"]]
      (let [params (get-in (spec-of n) [:function :parameters])]
        (is (= [] (:required params)) (str n " required"))
        (is (= {} (:properties params)) (str n " properties"))))))

(deftest offset-tools-declare-amount-and-unit
  (let [defs (tools/tools-def)
        spec-of (fn [n] (first (filter #(= n (get-in % [:function :name])) defs)))]
    (doseq [n ["time-offset" "date-offset"]]
      (let [params (get-in (spec-of n) [:function :parameters])]
        (is (contains? (:properties params) "amount"))
        (is (contains? (:properties params) "unit"))
        (is (= "integer" (get-in params [:properties "amount" :type])))
        (is (= #{"amount" "unit"} (set (:required params))))))))

(deftest tool-impls-include-time-and-date
  (let [impls (tools/make-tool-impls ["/tmp"] "Test")]
    (is (fn? (get impls "now")))
    (is (fn? (get impls "today")))
    (is (fn? (get impls "time-offset")))
    (is (fn? (get impls "date-offset")))
    (is (re-matches iso-datetime-re (:iso ((get impls "now") {}))))
    (is (re-matches iso-date-re     (:date ((get impls "today") {}))))
    (is (re-matches iso-datetime-re
                    (:iso ((get impls "time-offset") {:amount 5 :unit "minutes"}))))
    (is (re-matches iso-date-re
                    (:date ((get impls "date-offset") {:amount 1 :unit "weeks"}))))))