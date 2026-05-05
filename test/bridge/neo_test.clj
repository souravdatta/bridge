(ns bridge.neo-test
  (:require [clojure.test  :refer :all]
            [clojure.string :as str]
            [bridge.neo    :refer [run-bash run-powershell]]
            [bridge.tools  :as tools]))

;; ---------------------------------------------------------------------------
;; run-bash tests
;; ---------------------------------------------------------------------------

(deftest run-bash-no-confirm
  (testing "simple echo returns exit 0 and stdout"
    (let [result (run-bash "echo hello" false)]
      (is (str/includes? result "Exit: 0"))
      (is (str/includes? result "hello"))))

  (testing "failing command captures non-zero exit code"
    (let [result (run-bash "exit 42" false)]
      (is (str/includes? result "Exit: 42"))))

  (testing "stderr is captured"
    (let [result (run-bash "echo oops >&2" false)]
      (is (str/includes? result "---stderr---"))))

  (testing "result contains all three sections"
    (let [result (run-bash "echo hi" false)]
      (is (str/includes? result "Exit:"))
      (is (str/includes? result "---stdout---"))
      (is (str/includes? result "---stderr---")))))

(deftest run-bash-confirmation
  (testing "proceeds when user answers yes"
    (with-redefs [tools/ask-user (fn [& _] "yes")]
      (let [result (run-bash "echo confirmed")]
        (is (str/includes? result "Exit: 0")))))

  (testing "yes is case-insensitive"
    (with-redefs [tools/ask-user (fn [& _] "YES")]
      (let [result (run-bash "echo ok")]
        (is (str/includes? result "Exit: 0")))))

  (testing "throws :cancelled when user answers no"
    (with-redefs [tools/ask-user (fn [& _] "no")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled"
            (run-bash "echo never")))))

  (testing "throws :cancelled when dialog is closed (nil answer)"
    (with-redefs [tools/ask-user (fn [& _] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled"
            (run-bash "echo never")))))

  (testing "ex-info has :type :cancelled"
    (with-redefs [tools/ask-user (fn [& _] "nope")]
      (try
        (run-bash "echo never")
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :cancelled (:type (ex-data e)))))))))

;; ---------------------------------------------------------------------------
;; run-powershell tests
;; ---------------------------------------------------------------------------

(deftest run-powershell-no-confirm
  (testing "simple Write-Output returns exit 0 and stdout"
    (let [result (run-powershell "Write-Output hello" false)]
      (is (str/includes? result "Exit: 0"))
      (is (str/includes? result "hello"))))

  (testing "result contains all three sections"
    (let [result (run-powershell "Write-Output hi" false)]
      (is (str/includes? result "Exit:"))
      (is (str/includes? result "---stdout---"))
      (is (str/includes? result "---stderr---")))))

(deftest run-powershell-confirmation
  (testing "proceeds when user answers yes"
    (with-redefs [tools/ask-user (fn [& _] "yes")]
      (let [result (run-powershell "Write-Output confirmed")]
        (is (str/includes? result "Exit: 0")))))

  (testing "throws :cancelled when user answers no"
    (with-redefs [tools/ask-user (fn [& _] "no")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled"
            (run-powershell "Write-Output never")))))

  (testing "throws :cancelled when dialog is closed (nil answer)"
    (with-redefs [tools/ask-user (fn [& _] nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled"
            (run-powershell "Write-Output never")))))

  (testing "ex-info has :type :cancelled"
    (with-redefs [tools/ask-user (fn [& _] "nope")]
      (try
        (run-powershell "Write-Output never")
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :cancelled (:type (ex-data e)))))))))
