(ns bridge.memory)

;; EDN-backed persistence helpers.
;; Short-term memory lives in per-agent atoms.
;; Long-term memory is written to and read from EDN files under data/.
;;
;; Timestamps are stored as strings (.toString (java.time.Instant/now))
;; to survive EDN roundtrip without custom readers.

(defn hello
  "Hello from bridge.memory!"
  []
  (println "Hello from bridge.memory!"))
