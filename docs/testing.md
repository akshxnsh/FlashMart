# Testing notes

Manual runs against real processes, kept so they can be repeated. The
automated suites are described in `architecture.md` §8; this file is for runs
that need several services up at once.

## Day 5 failure demo: Order's payment step against the real payment-stub (2026-11-05)

**Label: verified under failure against the payment-stub, on one laptop,
once.** No load, no webhook (Day 6), no expiry sweep (Day 7), no outbox relay
or consumer, so no stock release was observed. One run of each scenario; the
timings are single observations, not distributions. The stub is not a real
gateway and not the Razorpay Clone.

### Setup

- Windows 11, Java 25.0.2, local PostgreSQL 18. `bash scripts/seed/seed.sh`
  run first (it also empties `orders`, `outbox`, `order_items`).
- Three real processes from `mvnw -DskipTests package` jars, run with
  `java -jar`:
  - `inventory-service` on 8082, `order-service` on 8083 (default config:
    payment connect 1 s, read 2 s, 2 attempts, 500 ms wait; breaker window 10,
    minimum 5, 50%, open 10 s, 3 half-open);
  - `payment-stub` on 127.0.0.1:8085, started with Tomcat's access log turned
    on so every call the stub receives is counted (launch flags only, no code):

    ```bash
    java -jar payment-stub/target/payment-stub-0.0.1-SNAPSHOT.jar \
      --server.tomcat.basedir="$TMP/stubtomcat" \
      --server.tomcat.accesslog.enabled=true \
      --server.tomcat.accesslog.directory="$TMP/stubaccess" \
      --server.tomcat.accesslog.buffered=false \
      --server.tomcat.accesslog.prefix=access --server.tomcat.accesslog.suffix=.log \
      --server.tomcat.accesslog.file-date-format= \
      '--server.tomcat.accesslog.pattern=%{HH:mm:ss.SSS}t "%r" %s %{ms}T ms key=%{Idempotency-Key}i'
    ```

    The timestamp in this log turned out to be when the stub *finished* the
    request (checked against Order's own timings below), not when it started.
- The buyer is the `X-Buyer-Id` header (temporary and forgeable until the Day 8
  gateway). Order does not check that the buyer exists, so buyers 101..113
  were used, one per request, so the one-live-order rule never interfered.
  Every order: sale 1, product 2 (799.00) x 1, so amount = 848.00 with the fee.

**How "the stub holds a payment" was checked.** The stub has no list
endpoint, and adding one would be a code change. Two probes on the key
`order-<id>`, sent straight to the stub in HEALTHY mode (the stub applies its
mode before the lookup):
- *same body* as Order sent: `202` returns the ref the stub holds (or creates
  one if it held none, so this is only used where a payment is expected);
- *different body*: `400 IDEMPOTENCY_KEY_REUSED` means it holds one; `202` means
  it held none, and the probe itself then creates a junk payment under that
  key (only the scenario-2 key got one).

The stub keys payments by `Idempotency-Key` in one map, so it can hold at most
one payment per key. A total count across keys was not observable.

### Results

| # | Scenario | HTTP | Time | Order row | Outbox | Stub calls for `order-<id>` | Stub holds a payment? |
|---|---|---|---|---|---|---|---|
| 1 | HEALTHY | `201` | 1.170 s (first request after start-up) | 12179 `PENDING_PAYMENT`, ref `pay_3acbd827...` | none | 1 (`202`, 18 ms) | yes, same ref (same-body probe) |
| 2 | ERROR_5XX | `503 PAYMENT_UNAVAILABLE` | 0.582 s | 12180 `PAYMENT_UNAVAILABLE`, no ref, detail "unavailable after 2 attempts: [500] ..." | 1 `OrderCancelled{PAYMENT_UNAVAILABLE}` | 2 (`500`, `500`; 514 ms apart) | no (different-body probe got `202`) |
| 3a | ERROR_5XX, next order | `503` | 0.588 s | 12181 `PAYMENT_UNAVAILABLE` | 1 | 2 (`500`, `500`) | not probed |
| 3b | ERROR_5XX, breaker now open | `503` | **0.068 s** | 12182 `PAYMENT_UNAVAILABLE`, detail "payment circuit breaker is open" | 1 | **0** | n/a |
| 3c | same | `503` | **0.037 s** | 12183, same detail | 1 | **0** | n/a |
| 3d | stub set HEALTHY, < 10 s after opening | `503` | **0.057 s** | 12184, "breaker is open" | 1 | **0** | n/a |
| 3e | after an 11 s wait: 4 orders | `201` x 4 | 0.046 to 0.060 s | 12185..12188 `PENDING_PAYMENT` | none | 1 each (`202`) | not probed |
| 4 | SLOW_AFTER 3000 ms | `503 PAYMENT_UNAVAILABLE` | 4.571 s | 12189 `PAYMENT_UNAVAILABLE`, **no ref**, detail "after 2 attempts: Read timed out" | 1 | 2 (`202` after 3004 ms, twice) | **yes**, `pay_f08b7b90...` (different-body probe `400 KEY_REUSED`; same-body probe returns that ref) |
| 5 | SLOW_AFTER 3000, switched to HEALTHY at +2.342 s | `201` | 2.577 s | 12190 `PENDING_PAYMENT`, ref `pay_5b04c322...` | none | 2 (one `202` in 2 ms = attempt 2; one `202` after 3006 ms = abandoned attempt 1) | yes, **the same ref** Order stored (same-body probe), and only one for the key |

Times are curl's `time_total` for `POST /api/orders`, so they include the
Inventory reserve call and Order's database writes.

### What it shows

1. **Happy path:** a real initiate over HTTP, the derived key `order-<id>`, and
   the PENDING_PAYMENT CAS storing the stub's ref. The stub holds that one payment.
2. **5xx is fast, not ~3.5 s.** Two quick `500`s plus the 500 ms wait = 0.58 s.
   The 3 s per attempt is the *timeout* ceiling; a gateway that answers `500`
   at once never reaches it. The expectation going in ("about one retry, ~3.5
   s") was wrong for this case. Order ends `PAYMENT_UNAVAILABLE` with exactly
   one `OrderCancelled{PAYMENT_UNAVAILABLE}`; the stub created nothing.
3. **The breaker opened one request earlier than planned, and that is
   correct.** The successful call in scenario 1 also counts: after scenario 3a
   the window held 5 calls (1 success, 4 failures = 80%), which meets the
   minimum of 5 and the 50% rate. From then on requests failed in 37 to 68 ms
   with **no call reaching the stub** (0 access-log lines), and stayed refused
   even after the stub was healthy again (3d). After the 11 s wait the next
   calls went through and succeeded. In this first run the breaker's state was
   not visible, so "half-open, then closed" was inferred; **part 2 below
   OBSERVED it** in Order's log.
4. **The lost-reply gap is real.** With SLOW_AFTER 3000 both attempts timed out
   at Order (2 s read timeout; 4.57 s total), Order answered `503` and cancelled
   the order, **but the stub created the payment on attempt 1** and returned
   the same one on attempt 2. That payment `pay_f08b7b90...` is now held by the
   stub for an order that is `PAYMENT_UNAVAILABLE` with no `payment_ref`. Nothing
   refunds it: the webhook handler (Day 6) and the reconciliation sweep do not
   exist. **Unrefunded and unhandled today.** This is the case the Day 6
   handler must catch by order id.
5. **The same key gave the same payment across the retry** (one run, timing
   dependent). Attempt 1 created `pay_5b04c322...` and its reply was abandoned;
   attempt 2, after the switch to HEALTHY, got the same ref back in 2 ms, Order
   stored it and answered `201`. The window was narrow: attempt 1 times out at
   about +2.0 s and attempt 2 starts at about +2.5 s, and the switch landed at
   +2.342 s. **Fragile**: a slower machine or a later switch would move it
   outside that 500 ms window. It worked once; it was not repeated.

### Observed but not explained: RESOLVED in part 2

After scenario 5 I expected the breaker to be open (by my count 3 of 5 calls
failed, 60%), but the next order, sent by hand, was still accepted (order
12191, `201`). **Resolved by part 2 below:** the breaker did open at exactly
that count; the hand-sent order most likely arrived after the 10 s open time
and was let through as the first half-open trial. Original text of this
section replaced; the run's numbers above are unchanged.

### Left behind after the run (relevant for Day 6 and 7)

- `orders`: 7 `PENDING_PAYMENT` (they expire only once the Day 7 sweep exists),
  6 `PAYMENT_UNAVAILABLE`; `outbox`: 6 `OrderCancelled` rows never published
  (no relay).
- Inventory item 2: `available_stock` 37 of 50, 13 `RESERVED` reservations. The
  6 cancelled orders' stock was **not** released, because nothing consumes
  `OrderCancelled` yet.
- The stub holds payments for every successful order, for scenario 4's
  cancelled order (the lost-reply payment), and one junk probe payment under
  scenario 2's key. It is in memory, so a restart clears it.

### The script

Runs scenarios 1 to 5 against already-running processes. Set `DB_USERNAME`,
`DB_PASSWORD` and `STUB_ACCESS_LOG` (the access log path from the launch flags
above); run `scripts/seed/seed.sh` first so buyers and stock start clean.

```bash
#!/usr/bin/env bash
# Day 5 failure demo: real inventory (8082), order (8083) and payment-stub (8085)
# processes on one laptop, local Postgres, seeded with scripts/seed/seed.sh.
# Needs DB_USERNAME / DB_PASSWORD in the environment, psql and curl on PATH.
# STUB_ACCESS_LOG: path of the stub's Tomcat access log (see docs/testing.md).
set -uo pipefail

ORDER=http://localhost:8083
STUB=http://127.0.0.1:8085
SALE=1
PRODUCT=2            # Court Tee, 799.00 -> amount 799.00 + 49.00 fee = 848.00
AMOUNT=848.00
export PGPASSWORD="$DB_PASSWORD"
q() { psql -w -h localhost -U "$DB_USERNAME" -d "$1" -Atc "$2"; }

mode() { curl -s -X POST "$STUB/admin/mode" -H 'Content-Type: application/json' \
           -d "{\"mode\":\"$1\",\"delayMs\":${2:-0}}" > /dev/null; echo "  [stub mode -> $1 ${2:-0} ms]"; }

# order <buyer> <key>: prints "<http> <seconds>" and leaves the body in /tmp-like $BODY
BODY=$(mktemp)
order() {
  curl -s -o "$BODY" -w '%{http_code} %{time_total}' -X POST "$ORDER/api/orders" \
    -H 'Content-Type: application/json' -H "X-Buyer-Id: $1" -H "Idempotency-Key: $2" \
    -d "{\"flashSaleId\":$SALE,\"items\":[{\"productId\":$PRODUCT,\"quantity\":1}]}"
}
order_id_of() { q order-service "select id from orders where buyer_id = $1 order by id desc limit 1"; }
row() { q order-service "select status, coalesce(payment_ref,'-'), amount, coalesce(failure_detail,'-') from orders where id = $1"; }
outbox() { q order-service "select coalesce(string_agg(payload_json::text, ' | '), 'none') from outbox where aggregate_id = $1"; }
stub_calls() { grep -c "key=order-$1\$" "$STUB_ACCESS_LOG"; }
stub_lines() { grep "key=order-$1\$" "$STUB_ACCESS_LOG" | sed 's/^/    stub: /'; }

# Does the stub hold a payment for key order-<id>? Probe with a DIFFERENT body:
# 400 IDEMPOTENCY_KEY_REUSED = it holds one; 202 = it held none (and the probe
# itself has now created a junk payment under that key). Runs in HEALTHY mode
# because the stub applies its mode before the lookup.
probe_held() {
  curl -s -w ' %{http_code}' -X POST "$STUB/api/payments/initiate" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: order-$1" -d '{"orderId":999999999,"amount":"0.01","currency":"INR"}'
}
# Same body as Order sent: 202 returns the ref the stub holds for that key.
probe_ref() {
  curl -s -X POST "$STUB/api/payments/initiate" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: order-$1" -d "{\"orderId\":$1,\"amount\":\"$AMOUNT\",\"currency\":\"INR\"}"
}

report() { # report <buyer> <http+time>
  local id; id=$(order_id_of "$1")
  echo "  HTTP+time: $2"
  echo "  body: $(cat "$BODY")"
  echo "  order $id row: $(row "$id")"
  echo "  outbox: $(outbox "$id")"
  echo "  stub calls for order-$id: $(stub_calls "$id")"; stub_lines "$id"
}

echo "== 1) HEALTHY"
mode HEALTHY
r=$(order 101 s1); report 101 "$r"; id=$(order_id_of 101)
echo "  stub holds (same-body probe): $(probe_ref "$id")"

echo "== 2) ERROR_5XX"
mode ERROR_5XX
r=$(order 102 s2); report 102 "$r"; id2=$(order_id_of 102)

echo "== 3) breaker: two more failing orders, then an instant refusal"
r=$(order 103 s3a); report 103 "$r"
r=$(order 104 s3b); report 104 "$r"
r=$(order 105 s3c); report 105 "$r"
mode HEALTHY
echo "  -- stub HEALTHY now, but the breaker is still open (< 10 s):"
r=$(order 106 s3d); report 106 "$r"
echo "  -- waiting 11 s for open-ms (10 s) to pass"
sleep 11
for b in 107 108 109 110; do r=$(order $b s3-$b); report $b "$r"; done
echo "  stub probe for order 2 (ERROR_5XX) after the fact: $(probe_held "$id2")"

echo "== 4) SLOW_AFTER 3000"
mode SLOW_AFTER 3000
r=$(order 111 s4); id4=$(order_id_of 111)
sleep 3.5   # let the stub finish its second (abandoned) reply so the log is complete
mode HEALTHY
report 111 "$r"
echo "  stub holds (different-body probe): $(probe_held "$id4")"
echo "  stub holds ref (same-body probe): $(probe_ref "$id4")"

echo "== 5) SLOW_AFTER 3000, switch to HEALTHY during the 500 ms wait"
mode SLOW_AFTER 3000
start=$(date +%s.%N)
( order 112 s5 > "$BODY.code" ) &
cpid=$!
sleep 2.2
mode HEALTHY
echo "  switched at +$(awk -v a="$start" -v b="$(date +%s.%N)" 'BEGIN{printf "%.3f", b-a}') s"
wait $cpid
sleep 1.5   # the first (abandoned) attempt is still sleeping in the stub
r=$(cat "$BODY.code"); report 112 "$r"; id5=$(order_id_of 112)
echo "  stub holds ref (same-body probe): $(probe_ref "$id5")"
echo "  stub holds (different-body probe): $(probe_held "$id5")"
```

## Day 5 failure demo, part 2: the breaker's transitions, observed (2026-11-05)

**Label: verified under failure against the payment-stub, on one laptop, one
run, no load, no webhook.** Same label as part 1. Written after adding the
breaker's WARN log (`feat(order): log every payment breaker state transition at
WARN`), to answer the open item in part 1.

### What changed and how it was run

- Code change: only a WARN log line per breaker state transition
  (`PaymentResilienceConfig`, through the breaker's event publisher); no
  threshold or behaviour change. A unit test drives the real breaker bean
  through CLOSED -> OPEN -> HALF_OPEN -> CLOSED and checks the lines.
- Run: `seed.sh` first (empties the Order DB), then inventory, order and the
  payment-stub started fresh, and **Order never restarted between scenarios**.
  Scenarios 3, 4, 5 back to back (`demo2.sh`, below), fresh buyers 201+. The
  stub's Tomcat access log counts calls, as in part 1.

### The transitions Order logged (all of them, in order)

| Time | Line | Caused by |
|---|---|---|
| 16:49:31.406 | `CLOSED -> OPEN` (failure rate 100.0%, 5 calls in window, 5 failed) | the 3rd failing order's first attempt: orders 1 and 2 gave 4 failed calls, this was the 5th (the minimum) |
| 16:49:44.911 | `OPEN -> HALF_OPEN` | the first order sent after the 11 s wait (order 12197), 13.5 s after opening |
| 16:49:46.082 | `HALF_OPEN -> CLOSED` | the third half-open trial (order 12199) |
| 16:49:58.905 | `CLOSED -> OPEN` (failure rate **60.0%**, 5 calls in window, 3 failed) | 1 ms after the scenario-5 order's second attempt succeeded at the stub (stub log 16:49:58.904) |

**Half-open is now OBSERVED, not inferred**: it is in Order's own log, and the
orders at both ends of it reached the stub and got `201`.

### Per step (HTTP, time, order, stub)

- Failing orders 1 and 2: `503`, 1.616 s (first request after start-up) and
  0.555 s, 2 stub calls each (two `500`s). Order 3: `503`, 0.575 s, 1 stub call
  then "payment circuit breaker is open" (its second attempt was refused).
- Two orders while open (stub already HEALTHY): `503` in **0.054 s and 0.044 s**,
  0 stub calls each.
- After the wait: four orders, all `201` in 0.047 to 0.077 s, 1 stub call each.
  The first of them was the `OPEN -> HALF_OPEN` call.
- Scenario 4 (SLOW_AFTER 3000): `503` in 4.556 s, order `PAYMENT_UNAVAILABLE`,
  no ref. The stub log shows **two** calls of 3005 ms each, so the payment was
  created on attempt 1; a same-body probe afterwards returned
  `pay_84b155dc...` (the stub holds it). Again unrefunded and unhandled: no
  webhook, no sweep. (The script printed "stub calls: 1" right after the
  response because the stub writes its access-log line when a request
  *finishes*, 3 s after it starts; the second line was not there yet. Counted
  again after the wait: 2, plus the probe's own line.)
- Scenario 5: `201` in 2.571 s, order `PENDING_PAYMENT` with `pay_946efc78...`,
  2 stub calls (a 2 ms `202` and a 3004 ms `202` for the abandoned attempt).
  The timing worked again with the switch at about +2.2 s. That makes 2 of 2
  runs, still timing dependent (a 500 ms window). The same-ref probe was not
  repeated in this run (it was in part 1); that there is one payment for the key
  follows from the stub's design and its own tests.
- Two orders after scenario 5 (stub HEALTHY): `503` in 0.038 s and 0.047 s,
  "payment circuit breaker is open", 0 stub calls.

### Answer to part 1's open item

**The breaker did open after scenario 5's failures**, at exactly the count I
had predicted: after it closed, the window held 1 success (order 12200), 2
timeouts (scenario 4), then scenario 5's timeout and success = 5 calls, 3
failed, 60% >= 50%. The call that tipped it over was the scenario-5 order's
own successful second attempt, so that order still got its `201`; the *next*
requests were refused.

So part 1's "still accepted" order was not an anomaly. Most likely (consistent
with this run, not directly observed in part 1): that order was sent by hand
minutes after scenario 5, long past the 10 s open time. resilience4j leaves
OPEN only when a call arrives (`OPEN -> HALF_OPEN` at 16:49:44.911 above is
13.5 s after opening, with no call in between), and that first call is let
through as a half-open trial. The unit test pins this lazy behaviour. I did not
change any threshold.

### Things to know about the log line

- The failure rate and call counts are read *after* the transition, from the
  new state's window. For `OPEN -> HALF_OPEN` and `HALF_OPEN -> CLOSED` that
  window is empty, so those lines print `failure rate -1.0%, 0 calls` (-1.0
  means "not enough calls to compute"). Only the `-> OPEN` lines carry the rate
  the decision was made on. Not changed here (the task was no other change).
- The unit test also pins two behaviours worth knowing: half-open is judged on
  its 3 trial calls at the same 50% threshold (1 failed trial of 3 closes the
  breaker, 2 of 3 reopen it), and time alone never moves OPEN to HALF_OPEN.

### Practical note: the demo dirties the Order tests

The entity tests assume empty Order tables. After the demo, the first full
Order run had 4 failures that were all row counts (orders with no
`payment_ref`, unpublished `outbox` rows left by the demo). After
`bash scripts/seed/seed.sh` (which empties those tables) the suite passed:
217 tests, 0 failures. Reset before running the suite.

### The script (part 2)

Runs scenarios 3, 4, 5 on already-running processes. Needs `DB_USERNAME`,
`DB_PASSWORD`, `STUB_ACCESS_LOG` (the stub's access log, flags above) and
`ORDER_LOG` (Order's console log redirected to a file).

```bash
#!/usr/bin/env bash
# Day 5 failure demo, part 2: scenarios 3 -> 4 -> 5 back to back on ONE running
# Order process (never restarted between them), with the breaker's WARN
# transition lines pulled from Order's log after every step.
# Needs DB_USERNAME, DB_PASSWORD, STUB_ACCESS_LOG and ORDER_LOG in the environment.
set -uo pipefail

ORDER=http://localhost:8083
STUB=http://127.0.0.1:8085
SALE=1
PRODUCT=2            # 799.00 + 49.00 fee = 848.00
AMOUNT=848.00
export PGPASSWORD="$DB_PASSWORD"
q() { psql -w -h localhost -U "$DB_USERNAME" -d "$1" -Atc "$2"; }
BODY=$(mktemp)

mode() { curl -s -X POST "$STUB/admin/mode" -H 'Content-Type: application/json' \
           -d "{\"mode\":\"$1\",\"delayMs\":${2:-0}}" > /dev/null; echo "  [stub mode -> $1 ${2:-0} ms]"; }
order() { # order <buyer> <key> -> "<http> <seconds>"
  curl -s -o "$BODY" -w '%{http_code} %{time_total}' -X POST "$ORDER/api/orders" \
    -H 'Content-Type: application/json' -H "X-Buyer-Id: $1" -H "Idempotency-Key: $2" \
    -d "{\"flashSaleId\":$SALE,\"items\":[{\"productId\":$PRODUCT,\"quantity\":1}]}"
}
order_id_of() { q order-service "select id from orders where buyer_id = $1 order by id desc limit 1"; }
detail() { q order-service "select status || ' | ' || coalesce(payment_ref,'-') || ' | ' || left(coalesce(failure_detail,'-'), 70) from orders where id = $1"; }
stub_calls() { grep -c "key=order-$1\$" "$STUB_ACCESS_LOG"; }

LOGPOS=0
breaker_lines() { # new breaker transition lines in Order's log since the last call
  local total; total=$(wc -l < "$ORDER_LOG")
  tail -n +"$((LOGPOS + 1))" "$ORDER_LOG" | grep 'payment circuit breaker' | sed 's/^/    ORDER LOG: /' \
    | sed -E 's/ INFO| WARN//; s/ --- \[order-service\] \[[^]]*\]//; s/c\.f\.o\.c\.p\.PaymentResilienceConfig +://'
  LOGPOS=$total
}

step() { # step <label> <buyer> <key>
  local r id; r=$(order "$2" "$3"); id=$(order_id_of "$2")
  printf '  %-34s %s s  HTTP %s  order %s [%s]  stub calls: %s\n' "$1" "$(echo "$r" | cut -d' ' -f2)" \
    "$(echo "$r" | cut -d' ' -f1)" "$id" "$(detail "$id")" "$(stub_calls "$id")"
  breaker_lines
}

echo "== start: $(date +%H:%M:%S.%N | cut -c1-12), Order log lines so far: $(wc -l < "$ORDER_LOG")"
breaker_lines

echo "== 3) ERROR_5XX until the breaker opens, then instant refusals"
mode ERROR_5XX
b=201
for i in 1 2 3; do step "failing order #$i" $b "s3-$b"; b=$((b+1)); done
mode HEALTHY
echo "  -- stub HEALTHY, breaker still inside its 10 s"
step "order while open" $b "s3-$b"; b=$((b+1))
step "order while open" $b "s3-$b"; b=$((b+1))
echo "  -- waiting 11 s (open-ms is 10 s); NO call is made during the wait"
sleep 11
breaker_lines
echo "  -- first call after the wait should flip OPEN -> HALF_OPEN"
for i in 1 2 3 4; do step "post-wait order #$i" $b "s3-$b"; b=$((b+1)); done

echo "== 4) SLOW_AFTER 3000: both attempts time out"
mode SLOW_AFTER 3000
step "slow-after order" $b "s4"; id4=$(order_id_of $b); b=$((b+1))
sleep 3.5   # let the stub finish its abandoned replies
mode HEALTHY
echo "  stub holds a payment for order-$id4 (same-body probe):"
echo "    $(curl -s -X POST "$STUB/api/payments/initiate" -H 'Content-Type: application/json' -H "Idempotency-Key: order-$id4" -d "{\"orderId\":$id4,\"amount\":\"$AMOUNT\",\"currency\":\"INR\"}")"
breaker_lines

echo "== 5) SLOW_AFTER 3000, switched to HEALTHY inside the 500 ms wait"
mode SLOW_AFTER 3000
( order $b "s5" > "$BODY.code" ) &
cpid=$!
sleep 2.2
mode HEALTHY
wait $cpid
sleep 1.5
r=$(cat "$BODY.code"); id5=$(order_id_of $b)
printf '  %-34s %s s  HTTP %s  order %s [%s]  stub calls: %s\n' "scenario-5 order" "$(echo "$r" | cut -d' ' -f2)" \
  "$(echo "$r" | cut -d' ' -f1)" "$id5" "$(detail "$id5")" "$(stub_calls "$id5")"
b=$((b+1))
breaker_lines

echo "== after 5: did the breaker open? probe with orders while the stub is HEALTHY"
for i in 1 2; do step "probe order #$i" $b "probe-$b"; b=$((b+1)); done
echo "== end"
```
