# Bridge — Actor-Model Communication Layer: Implementation Plan

Self-contained plan captured from a design thread. Work through the phases in order; each phase leaves the system in a runnable state.

---

## 0. Decisions baked in

- **Actor model** over sync function calls. Each bridge-agent is an independent unit.
- **`core.async` channels** for inboxes and control signals.
- **Clojure primitive agents** (`(agent state :error-mode :continue)` + `send-off`) hold per-agent chat / working context. Serialises work *within* an agent; different agents parallelise.
- **Global context** is a single atom keyed by agent keyword. Every entry's work channel is under the key `:chan` — explicit that it's a channel.
- **User is a registered endpoint**, owned by Motoko. Other agents route user-facing output through `:motoko` — they never write to `:user :chan` directly.
- **Logs are a registered endpoint**, sliding-buffer channel.
- **Control channel per agent** (`:control`) for graceful `:stop`, `:pause`, `:resume`, `:drain`, `:health`. Not for work.
- **Hop count on every envelope**, max 10. Over-limit messages route to `:motoko` as an error envelope.
- **Buffer types**:
  - Agent `:chan` — `(chan 64)` bounded-blocking.
  - Agent `:control` — `(chan 4)` bounded-blocking.
  - `:user :chan` — `(chan 32)` bounded-blocking.
  - `:logs :chan` — `(chan (sliding-buffer 128))` sliding.
- **Graceful restart** is per-agent, not cascade. Motoko crashing does **not** restart others.
- **Message loss during restart is acceptable for now**. Durable queues (Redis / SQS) land later; envelope shape does not change when they do.

---

## 1. Global context shape

```clojure
(defonce ^:private ctx
  (atom
   {:user    {:chan <user-chan>}
    :logs    {:chan <log-chan>}

    :motoko  {:chan      <inbox>
              :control   <ctl>
              :ctx-agent <clj-agent>
              :handler   motoko/handler
              :worker    <go-loop-ref>
              :status    :running}
    :neo     {:chan ... :control ... :ctx-agent ... :handler neo/neo    :worker ... :status ...}
    :gandalf {...}
    :asimov  {...}
    :uhura   {...}
    :quorra  {...}}))
```

- Minimal entries (`:user`, `:logs`) only have `:chan`.
- Full agent entries have `:chan`, `:control`, `:ctx-agent`, `:handler`, `:worker`, `:status`.

---

## 2. New namespace: `bridge.comms`

Single file, ~200 lines. This is the whole transport layer.

### 2.1 Public API

```clojure
;; Registration & lifecycle
(register-agent!   kw handler & {:keys [buf ctl-buf init-state]})
(register-endpoint! kw & {:keys [buf kind]}) ; :kind can be :bounded (default) or :sliding
(start-all!)
(stop-all!)
(restart! kw)

;; Message passing
(send!    target msg)                 ; put on (get-in @ctx [target :chan])
(control! target ctl-msg)             ; put on (get-in @ctx [target :control])

;; Observability
(status kw)
(agents-list)
```

### 2.2 Go-loop skeleton (inside `register-agent!`)

```clojure
(go-loop [paused? false]
  (let [ports  (if paused? [control] [chan control])
        [v ch] (alts! ports)]
    (cond
      (= ch control)
      (case (:op v)
        :stop    nil                                      ; exit
        :pause   (recur true)
        :resume  (recur false)
        :drain   (do (drain-inbox chan ctx-agent handler) nil)
        :health  (do (when-let [r (:reply-ch v)] (>! r :ok)) (recur paused?))
        (recur paused?))                                  ; unknown -> ignore

      (some? v)
      (do (send-off ctx-agent handler v) (recur paused?))

      :else nil)))                                        ; inbox closed
```

### 2.3 `send!` with hop-count guard

```clojure
(def ^:private max-hops 10)

(defn send! [target msg]
  (let [msg' (update msg :msg/hop-count (fnil inc 0))]
    (if (> (:msg/hop-count msg') max-hops)
      (when (not= target :motoko)                         ; avoid recursion
        (send! :motoko (hop-overflow-envelope msg')))
      (when-let [ch (get-in @ctx [target :chan])]
        (a/put! ch msg')))))
```

### 2.4 `start-all!` — fixed order

1. Register `:logs` endpoint (sliding buffer) and spawn log-writer worker.
2. Register and start `:neo`, `:gandalf`, `:asimov`, `:uhura`, `:quorra` (any order among these).
3. Register and start `:motoko` last.
4. Register `:user` endpoint.

### 2.5 `stop-all!` — reverse, graceful

1. Stop REPL / input source writing to `:motoko`.
2. `(control! :motoko {:op :drain})`.
3. Each downstream agent: `(control! kw {:op :drain})`.
4. Close `:user :chan`.
5. Close `:logs :chan`.

### 2.6 `restart! kw`

1. `(control! kw {:op :stop})`.
2. Await worker exit with timeout (say 5s); else force by closing channels.
3. Re-register with same handler and (optionally preserved) `:ctx-agent` state.

---

## 3. Envelope changes (`bridge.protocol`)

Add `:msg/hop-count` (int) to every envelope. Default 0 on creation.

```clojure
(defn make-request [& opts]
  (assoc base-map :msg/hop-count 0 ...))

(defn forward [request target & opts]
  (-> request
      (assoc :msg/id (fresh-msg-id) ... )
      (update :msg/hop-count (fnil inc 0))))
```

Header spec:
```clojure
(s/def :msg/hop-count (s/and int? (complement neg?)))
```

Add `:msg/hop-count` to the `::envelope` spec's required keys.

---

## 4. Handler signature — new shape for every agent

### 4.1 Before (current)

```clojure
(defn neo [request]
  (proto/make-reply request :response "..." :use true))
```

### 4.2 After

```clojure
(defn neo
  "Invoked via (send-off ctx-agent neo msg). Takes [state msg], returns new state.
  Writes the outgoing reply / next-hop envelope to another channel as a side-effect."
  [state msg]
  (let [reply (proto/make-reply msg :response "..." :use true)]
    (bridge.comms/send! :motoko reply)
    state))                                                ; unchanged for stubs
```

- **No return value consumed by caller.** The go-loop only cares about the updated state.
- **Side-effect**: write to next channel.
- Default next channel for a downstream agent = `:motoko`. Motoko decides what to do with it (forward to user, hand to another agent, etc.).

### 4.3 Apply this to each agent file

- [src/bridge/neo.clj](src/bridge/neo.clj)
- [src/bridge/gandalf.clj](src/bridge/gandalf.clj)
- [src/bridge/asimov.clj](src/bridge/asimov.clj)
- [src/bridge/uhura.clj](src/bridge/uhura.clj)
- [src/bridge/quorra.clj](src/bridge/quorra.clj)

---

## 5. Motoko handler — the special one

`bridge.motoko/handler` takes `[state msg]`:

- If `:msg/from` is `:user`:
  - Slash command → `switch-agent!`, write confirmation to `:user :chan`.
  - Active-agent ≠ `:motoko` → `(comms/send! @active-agent msg)`.
  - Active-agent = `:motoko`:
    - Pattern match → classify → either write reply to `:user :chan` (motoko-local) or `(comms/send! target forwarded-request)`.
    - No match → `consult-omni` → forward.
- If `:msg/from` is another agent (a reply coming back):
  - Heuristic / explicit flag to decide: user-facing → `:user :chan`; next-hop → `comms/send! to-next-agent`.
- If `:msg/kind` is `:event` (e.g. from Gandalf's scheduler): treat as a request originated by `:motoko` and route accordingly.

### 5.1 Remove now-obsolete bits

- `agent-dispatch-map` — replaced by `comms` registry lookup.
- The synchronous `forward-to-agent` fn — replaced by `comms/send!`.
- The `*agent-dispatch*` dynamic var — replaced by `comms/send!` via a `:agent` followup handler.

### 5.2 Keep as-is

- Pattern engine, classification result shape, slash-command parsing, active-agent atom, switch-messages, forward-lines, omni-session, `parse-omni-response`, followup interpreter (time / date math).

---

## 6. `core.clj` — REPL wiring

```clojure
(defn -main [& _]
  (comms/start-all!)
  (let [user-ch (get-in @ctx [:user :chan])]
    (loop []
      (print "> ") (flush)
      (let [line (read-line)]
        (when line
          (comms/send! :motoko
                       (proto/make-request :from :user :to :motoko :content line))
          ;; Read whatever arrives on :user within a timeout; print each.
          (loop []
            (let [[reply _] (a/alts!! [user-ch (a/timeout 30000)])]
              (when reply
                (println (:response reply))
                (when (:expect-more? reply) (recur)))))
          (recur))))
    (comms/stop-all!)))
```

Status / "thinking..." interleavings arrive on `:user :chan` as separate messages.

---

## 7. Dependencies

Add to [project.clj](project.clj):

```clojure
[org.clojure/core.async "1.6.681"]
```

(or latest stable).

---

## 8. Phase-by-phase plan

Each phase is independently testable; the system runs correctly at the end of each.

### Phase 1 — `bridge.comms` skeleton

- Create [src/bridge/comms.clj](src/bridge/comms.clj).
- Implement `ctx` atom, `register-endpoint!`, `register-agent!`, `send!`, `control!`, `start-all!`, `stop-all!`, `restart!`, `status`.
- Add log-writer worker.
- Add core.async dependency.
- **Verification**: register two dummy agents with echo handlers; `send!` a ping; confirm reply arrives via log; `stop-all!` cleanly.

### Phase 2 — Envelope hop-count

- Add `:msg/hop-count` to [src/bridge/protocol.clj](src/bridge/protocol.clj).
- Update `make-request`, `make-reply`, `forward`, `make-event`.
- Hop-count guard inside `comms/send!`.
- **Verification**: craft an envelope with `:msg/hop-count 10`, call `send!`, confirm error envelope routes to `:motoko`.

### Phase 3 — Rewrite stubs to new handler signature

- For each of [neo.clj](src/bridge/neo.clj), [gandalf.clj](src/bridge/gandalf.clj), [asimov.clj](src/bridge/asimov.clj), [uhura.clj](src/bridge/uhura.clj), [quorra.clj](src/bridge/quorra.clj):
  - Change fn arity from `[request]` to `[state msg]`.
  - Compute reply via `proto/make-reply`.
  - `(comms/send! :motoko reply)`.
  - Return `state`.
- **Verification**: standalone — register one agent, send a request, confirm reply appears on `:motoko` inbox (inspect atom).

### Phase 4 — Refactor Motoko

- Rewrite [src/bridge/motoko.clj](src/bridge/motoko.clj) handler to `[state msg]` shape.
- Replace every `forward-to-agent` call-site with `comms/send!`.
- Handle inbound replies (`:msg/from` = agent) — route to `:user` or next agent.
- Remove `agent-dispatch-map`, `forward-to-agent`, `*agent-dispatch*`.
- Keep everything else (pattern engine, slash commands, omni, followup resolver).
- **Verification**: `start-all!`, send a greeting via `send! :motoko …`, confirm reply arrives on `:user :chan`.

### Phase 5 — Wire `core.clj`

- `-main` calls `comms/start-all!`, enters REPL loop.
- REPL reads `:user :chan` with a timeout after each send.
- On EOF: `comms/stop-all!`.
- **Verification**: `lein run`; type "hi"; see Motoko's greeting. Try `/neo write a python script`; see Neo's stub reply.

### Phase 6 — Integration smoke tests

- Greeting path (user → motoko → user).
- Route path (user → motoko → neo → motoko → user).
- Slash path (`/neo`, then plain input → neo directly).
- Omni path (an unclassified prompt → consult-omni → target agent).
- Hop-count limit (craft a deliberately looping chain → capped at 10).
- Control: `(control! :neo {:op :pause})`, send request, confirm it queues; `(control! :neo {:op :resume})`, confirm drain.
- Graceful shutdown: `(stop-all!)`, confirm all go-loops exit.

### Phase 7 — Later: durable queues

- Swap `core.async` channels for Redis lists or SQS queues behind the same `comms` API.
- Envelope shape unchanged.
- Add replay / dedup via `:msg/id`.
- Out of scope for this file.

---

## 9. Gotchas / things to remember

1. **Never block inside a go-loop body.** Only `alts!` + `send-off`. All blocking work happens inside the Clojure-agent fn, which runs on `send-off`'s unbounded thread pool.
2. **`:error-mode :continue`** on every Clojure agent. Otherwise one bad LLM call wedges that agent forever.
3. **Go-loop exits on `nil` from `<!`** (channel closed). Handle that branch explicitly.
4. **`:user :chan` is read by the REPL only.** Agents never read from it.
5. **Agents write to `:motoko :chan`, never to `:user :chan`.** Motoko relays.
6. **Bounded-blocking for work channels, sliding for logs.** Don't drop user work silently.
7. **Circular-route detection** = hop-count. Also useful: dump `:msg/thread-id` + `:msg/id` chain in logs when debugging.
8. **Slash commands still go through `:motoko`.** They're just a kind of user message.
9. **Motoko's restart does NOT restart other agents.** They keep running.
10. **`send!` to an unknown target is a silent drop.** Log it, don't throw — during boot there's a brief window where not all agents are registered.

---

## 10. Open micro-decisions to make during implementation

- Exact log format — newline-delimited EDN? JSON lines? Start with EDN.
- Whether to allow agents to set `:expect-more? true` on replies (so REPL waits for status updates before returning control). Recommended yes.
- Where `active-agent` lives — still as a `defonce` atom in [motoko.clj](src/bridge/motoko.clj), or move to Motoko's `ctx-agent` state. Recommended: move it into `ctx-agent` state, so restart clears it naturally.
- How to seed `ctx-agent` state for agents that already have real state (e.g. Quorra — `{:session quorra-session}`). Pass `:init-state` to `register-agent!`.

---

## 11. Files touched, at a glance

| File | Change |
|---|---|
| `project.clj` | Add `[org.clojure/core.async "1.6.681"]`. |
| `src/bridge/comms.clj` | **NEW** — transport module. |
| `src/bridge/protocol.clj` | Add `:msg/hop-count` to envelope schema + builders. |
| `src/bridge/motoko.clj` | Handler → `[state msg]`; drop `agent-dispatch-map`, `forward-to-agent`, `*agent-dispatch*`; use `comms/send!`. |
| `src/bridge/neo.clj` | Handler → `[state msg]`; write reply via `comms/send!`. |
| `src/bridge/gandalf.clj` | Same as neo. |
| `src/bridge/asimov.clj` | Same as neo. |
| `src/bridge/uhura.clj` | Same as neo. |
| `src/bridge/quorra.clj` | Same as neo; keep the LLM call inside the handler body. |
| `src/bridge/core.clj` | Start comms, run REPL loop reading `:user :chan`. |

---

## 12. Non-goals for this plan

- Real tool access for Quorra (web search, image gen) — separate track.
- Asimov deep-research sub-agents — separate track.
- NLP middle tier between rules and LLM — separate track.
- Multi-user support — separate track; solved by per-thread-id `:user` endpoints.
- AWS deployment — plan compatible but not part of these phases.

---

## 13. Definition of done

- `lein run` boots the system, REPL accepts user input.
- Greetings, code requests, slash commands, and omni fallback all work end-to-end.
- `(comms/stop-all!)` cleanly terminates every go-loop without leaking threads.
- `(comms/restart! :motoko)` brings her back up without disturbing other agents.
- Hop-count loop is capped at 10 with a visible error returning to the user.
- No direct function-call dispatch remains; every inter-agent message goes through a channel.
