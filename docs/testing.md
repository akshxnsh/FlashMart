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
   handler must catch by order id. *(Update 2026-11-06: the Day 6 handler now
   records a `refunds` row when a SUCCESS webhook arrives for a
   `PAYMENT_UNAVAILABLE` order, which is tested in the Order suite. This exact
   scenario was not re-run, and nothing pays the refund out because the refund
   worker is not built.)*
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

- `orders`: 7 `PENDING_PAYMENT` (they expire only once the Day 7 sweep exists;
  the sweep has since been built and tested, Day 7),
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

## Day 6 end to end: webhook, outbox relay and a real Kafka (2026-11-05)

**Label: verified against real processes and the docker-compose Kafka, on one
laptop.** The full script was run 3 times. Run 1's Kafka read-back was broken
by a script bug (Git Bash rewrote `/opt/kafka/...` inside `docker compose exec`),
so its "0 messages" lines are void; runs 2 and 3 read Kafka correctly. The
tables below are run 3, with run 2 noted where it differed. No load. The gateway
is the payment-stub, not a real gateway and not the Razorpay Clone. Nothing
consumes the topics (Inventory's consumer is Day 7), so no stock confirmation or
release was observed.

### Setup

- Windows 11, Java 25.0.2, local PostgreSQL 18, Docker Desktop 29.9.2 with
  **only** the Kafka service from `docker-compose.yml` (`docker compose up -d
  kafka`, image `apache/kafka:4.3.1`, reported `healthy`). Postgres from compose
  was not started (the local one owns 5432).
- `inventory-service` (8082), `order-service` (8083, `--kafka.create-topics=true`,
  `--spring.jpa.show-sql=false`) and `payment-stub` (127.0.0.1:8085) from
  `mvnw -DskipTests package` jars, run with `java -jar`; default config otherwise
  (relay poll 1 s, batch 50, ack timeout 5 s; stub webhook retry every 2 s for up
  to 120 s, 1 s per attempt).
- `DB_USERNAME`, `DB_PASSWORD`, `PAYMENT_WEBHOOK_SECRET` exported from the
  repo-root `.env` (gitignored) into every process; never printed.
- `scripts/seed/seed.sh` first. Every order: sale 1, product 2 (799.00) x 1, so
  amount 848.00 with the fee. Buyers 201..205, one per order.
- Order created both topics itself on start-up (`kafka-topics.sh --list`:
  `__consumer_offsets order-cancelled payment-confirmed`).

### Results (run 3)

| # | Scenario | What was sent | Observed |
|---|---|---|---|
| 1 | Happy path | order 17007, then `/admin/webhook` SUCCESS | `201 PENDING_PAYMENT`; webhook `200` on attempt 1; order `PAID`; outbox row published about 2 s later, `attempts` 0; 0 refunds; one message on `payment-confirmed`, key `17007`, value `{"items": [{"quantity": 1, "subtotal": "799.00", "product_id": 2, "unit_price": "799.00", "product_name": "Zephyr Court Tee"}], "amount": "848.00", "buyer_id": 201, "order_id": 17007}`: money as strings, no `brand_id`, no envelope (key order is jsonb's) |
| 2 | Same SUCCESS x3 | `times: 3` on order 17007 | three deliveries, each `200` on attempt 1; still `PAID`; 0 refunds; still **1** message for 17007 |
| 3 | Another ref on a PAID order, twice | `payment_ref: pay_second_17007`, sent twice | `200` both times; **1** refund row (`pay_second_17007 848.00 PENDING`) after the first and still 1 after the second; order still `PAID` with its own ref |
| 4 | Wrong amount | order 17008, SUCCESS with `amount: "1.00"` | `200`; order stays `PENDING_PAYMENT`; 1 refund row for the held ref with amount **1.00** (the webhook's); no outbox row |
| 5 | Early webhook | auto SUCCESS + `BEFORE_REPLY`, order 17009 | stub log: attempt 1 at +9 ms -> `409 TOO_EARLY`; attempt 2 at +2023 ms -> `200 PAID`. Order answered the buyer `201 PENDING_PAYMENT`, then ended `PAID`; 1 message. Run 2: +9 ms `409`, +2020 ms `200` |
| 6 | Bad signature | `X-Signature: 00ff`, sent with curl straight to Order | `401 INVALID_SIGNATURE`; order 17008 unchanged |
| 7 | Event outlives an Order restart | Order restarted with `outbox.relay.enabled=false`; order 17010 paid; Order stopped; restarted with the relay on | relay off: order `PAID`, outbox row `published_at` NULL, 0 messages. After restart with the relay on: published within the start-up (published_at 22:43:50.03, before the script saw the health check pass at 22:43:50.27); 1 message |
| 8 | Kafka paused | order 17011; `docker compose pause kafka`; SUCCESS; 31.5 s later `unpause` | webhook `200`, order `PAID` throughout. Outbox row sampled every ~5 s: `attempts` 0, 1, 2, 3, 4, 5 (one failed pass about every 6 s: 5 s ack/metadata wait + 1 s poll). First `last_error`: `no broker ack within 5000 ms`; later ones `Topic payment-confirmed not present in metadata after 5000 ms`. Published at 22:44:38.12, i.e. as soon as the broker resumed (the unpause command returned at 22:44:38.20). **2 messages** for 17011 (see below). Run 2: same pattern, 2 messages for 17006 |

Totals, run 3: 4 outbox rows, 0 unpublished; messages per paid order 1, 1, 1, **2**.

### What it shows

- Every Day 6 webhook rule held against real processes: redelivery is a no-op,
  a second ref is refunded exactly once, a wrong amount is refunded and does not
  pay, a bad signature is `401`, and the early webhook is `409` and then applied
  on the stub's retry 2 s later.
- No event was lost in any scenario: a row written while the relay was off, or
  while Kafka was paused, was published once the relay or the broker was back.
- **A duplicate, observed 2 of 2 times (runs 2 and 3) with Kafka paused.** The
  two copies of 17011 are offsets 13 and 14 with producer timestamps 22:44:07.097
  (the relay's first send, 0.4 s after the pause) and 22:44:38.111 (the re-send
  after the unpause). Order's log at 22:44:12 shows the client timing out,
  "Cancelled in-flight PRODUCE request ... due to node 1 being disconnected",
  and bumping its producer epoch. The likely explanation, consistent with the
  log but not proven: the paused broker still held that request and appended it
  when it resumed, and the relay's later send was a new record under the new
  epoch, which the idempotent producer does not deduplicate. This is the
  at-least-once behaviour decided in Day 6, point 6: consumers must be
  idempotent. Run 1's topic also holds two copies of its paused-scenario order
  (17001), seen after the script was fixed.
- During the pause each failed pass held its row lock for about 5 to 6 s (the
  ack or metadata timeout), as designed.

### Not shown

- Not a real gateway and not the Razorpay Clone: the stub's retry policy (every
  2 s, give up at 120 s) is its own assumption.
- Not a crash at the exact instant between the ack and the commit: scenario 7
  stops Order (the shell's `kill`, while nothing was in flight) with the relay
  off, which simulates "written but not yet published", nothing more.
- No consumer service, so nothing de-duplicates the duplicate here.
- One laptop, one broker, one partition, no load, no timings beyond the single
  observations above.

### Left behind after the run

The Order database held the rows of the last run; `seed.sh` was run right
after, before the test suite (4 Order tests count absolute rows). The
`payment-confirmed` topic holds all three runs' messages until the container is
removed (`docker compose stop kafka` keeps them).

### The script

Run from the repo root in Git Bash, after `docker compose up -d kafka` is
healthy and the three jars are built. It starts and stops its own processes.

```bash
#!/usr/bin/env bash
# Day 6 end-to-end run: real inventory (8082), order (8083) and payment-stub
# (8085) processes, local Postgres, the docker-compose Kafka. One laptop.
#
# Run from the repo root, in Git Bash, after:
#   docker compose up -d kafka            (and wait for "healthy")
#   (cd order-service && ./mvnw -q -DskipTests package)   # same for inventory-service, payment-stub
# Reads DB_USERNAME, DB_PASSWORD, PAYMENT_WEBHOOK_SECRET from ./.env (never
# printed). LOGDIR (default $TMP/flashmart-e2e, outside the repo) gets the logs.
set -uo pipefail
set -a; . ./.env; set +a
export PGPASSWORD="$DB_PASSWORD"
LOGDIR="${LOGDIR:-${TMP:-/tmp}/flashmart-e2e}"; mkdir -p "$LOGDIR"

ORDER=http://localhost:8083
STUB=http://127.0.0.1:8085
SALE=1; PRODUCT=2        # Court Tee 799.00 + 49.00 fee = 848.00

# Git Bash rewrites /opt/... into a Windows path unless told not to.
kx() { MSYS_NO_PATHCONV=1 docker compose exec -T kafka "$@"; }
q() { psql -w -h localhost -U "$DB_USERNAME" -d "$1" -Atc "$2"; }
ts() { date +%H:%M:%S.%3N; }
wait_up() { # wait_up <url> : until anything answers
  for _ in $(seq 1 120); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "$1")" != "000" ] && return 0; sleep 1
  done; echo "!! $1 never came up"; exit 1
}
start_order() { # start_order <log> [extra args]
  local log="$1"; shift
  java -jar order-service/target/order-service-0.0.1-SNAPSHOT.jar --spring.jpa.show-sql=false \
    --kafka.create-topics=true "$@" > "$LOGDIR/$log" 2>&1 &
  ORDER_PID=$!; wait_up "$ORDER/actuator/health"; echo "  [$(ts) order up, pid $ORDER_PID, $*]"
}
stop_order() { kill "$ORDER_PID"; wait "$ORDER_PID" 2>/dev/null; echo "  [$(ts) order stopped]"; }

create() { # create <buyer> -> prints the order id; body to stdout of the caller via $LAST
  LAST=$(curl -s -w ' HTTP %{http_code}' -X POST "$ORDER/api/orders" -H 'Content-Type: application/json' \
    -H "X-Buyer-Id: $1" -H "Idempotency-Key: e2e-$1" \
    -d "{\"flashSaleId\":$SALE,\"items\":[{\"productId\":$PRODUCT,\"quantity\":1}]}")
  echo "  create buyer $1: $LAST" >&2
  q order-service "select id from orders where buyer_id = $1 order by id desc limit 1"
}
hook() { # hook '<json>' : /admin/webhook on the stub
  echo "  stub /admin/webhook $1 -> $(curl -s -X POST "$STUB/admin/webhook" -H 'Content-Type: application/json' -d "$1")"
}
row() { q order-service "select status || ' ref=' || coalesce(payment_ref,'-') || ' amount=' || amount from orders where id = $1"; }
refunds() { q order-service "select count(*) || ' row(s) ' || coalesce(string_agg(payment_ref || ' ' || amount || ' ' || status, '; '), '') from refunds where order_id = $1"; }
outbox() { q order-service "select coalesce(string_agg(event_type || ' published_at=' || coalesce(published_at::text,'NULL') || ' attempts=' || attempts || ' last_error=' || coalesce(left(last_error, 160),'-'), ' | '), 'none') from outbox where aggregate_id = $1"; }
# Messages on payment-confirmed with key <order id>, read inside the container.
messages() {
  kx /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 \
    --topic payment-confirmed --from-beginning --formatter-property print.key=true --formatter-property key.separator=' => ' \
    --timeout-ms 6000 2>/dev/null | grep "^$1 => "
}
count_messages() { messages "$1" | wc -l | tr -d ' '; }

echo "== setup $(ts)"
bash scripts/seed/seed.sh > /dev/null && echo "  seeded"
java -jar inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar > "$LOGDIR/inventory.log" 2>&1 &
INV_PID=$!
java -jar payment-stub/target/payment-stub-0.0.1-SNAPSHOT.jar > "$LOGDIR/stub.log" 2>&1 &
STUB_PID=$!
wait_up "$STUB/admin/mode"; wait_up http://localhost:8082/api/reservations
start_order order-1.log
echo "  topics: $(kx /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list | tr '\n' ' ')"

echo "== 1) happy path $(ts)"
id1=$(create 201); echo "  order $id1: $(row "$id1")"
hook "{\"order_id\":$id1}"
sleep 3
echo "  order: $(row "$id1")"; echo "  outbox: $(outbox "$id1")"; echo "  refunds: $(refunds "$id1")"
echo "  payment-confirmed messages for $id1:"; messages "$id1" | sed 's/^/    /'

echo "== 2) same SUCCESS x3 $(ts)"
hook "{\"order_id\":$id1,\"times\":3}"
sleep 3
echo "  order: $(row "$id1")"; echo "  refunds: $(refunds "$id1")"
echo "  messages for $id1: $(count_messages "$id1")"

echo "== 3) another ref on the PAID order, sent twice $(ts)"
hook "{\"order_id\":$id1,\"payment_ref\":\"pay_second_$id1\"}"
echo "  refunds after 1st: $(refunds "$id1")"
hook "{\"order_id\":$id1,\"payment_ref\":\"pay_second_$id1\"}"
echo "  refunds after 2nd: $(refunds "$id1")"; echo "  order: $(row "$id1")"

echo "== 4) wrong amount $(ts)"
id4=$(create 202); echo "  order $id4: $(row "$id4")"
hook "{\"order_id\":$id4,\"amount\":\"1.00\"}"
echo "  order: $(row "$id4")"; echo "  refunds: $(refunds "$id4")"; echo "  outbox: $(outbox "$id4")"

echo "== 5) early webhook: auto SUCCESS, BEFORE_REPLY $(ts)"
curl -s -X POST "$STUB/admin/auto-webhook" -H 'Content-Type: application/json' \
  -d '{"status":"SUCCESS","timing":"BEFORE_REPLY"}' > /dev/null
id5=$(create 203); echo "  order $id5 right after create: $(row "$id5")"
sleep 5
curl -s -X POST "$STUB/admin/auto-webhook" -H 'Content-Type: application/json' -d '{"status":"OFF"}' > /dev/null
echo "  order: $(row "$id5")"; echo "  outbox: $(outbox "$id5")"
echo "  stub log:"; grep "webhook order=$id5 " "$LOGDIR/stub.log" | sed 's/.*: webhook/    webhook/' | cut -c1-170
echo "  messages for $id5: $(count_messages "$id5")"

echo "== 6) bad signature $(ts)"
echo "  -> $(curl -s -w ' HTTP %{http_code}' -X POST "$ORDER/webhooks/payment" -H 'Content-Type: application/json' \
  -H 'X-Signature: 00ff' -d "{\"order_id\":$id4,\"payment_ref\":\"x\",\"status\":\"SUCCESS\",\"amount\":\"848.00\"}")"
echo "  order $id4 unchanged: $(row "$id4")"

echo "== 7) relay off, stop, restart with relay on $(ts)"
stop_order
start_order order-2-relay-off.log --outbox.relay.enabled=false
id7=$(create 204); hook "{\"order_id\":$id7}"
sleep 4
echo "  order: $(row "$id7")"; echo "  outbox (relay off): $(outbox "$id7")"
echo "  messages for $id7 (relay off): $(count_messages "$id7")"
stop_order
start_order order-3.log
sleep 4
echo "  outbox (relay on): $(outbox "$id7")"; echo "  messages for $id7: $(count_messages "$id7")"

echo "== 8) Kafka paused $(ts)"
id8=$(create 205)
docker compose pause kafka > /dev/null 2>&1; PAUSED=$(date +%s); echo "  [$(ts) kafka paused]"
hook "{\"order_id\":$id8}"
echo "  order: $(row "$id8")"
for i in 1 2 3 4 5 6; do sleep 5; echo "  +$(( $(date +%s) - PAUSED ))s outbox: $(outbox "$id8")"; done
docker compose unpause kafka > /dev/null 2>&1; UNPAUSED=$(date +%s); echo "  [$(ts) kafka unpaused]"
for i in $(seq 1 120); do
  p=$(q order-service "select published_at is not null from outbox where aggregate_id = $id8")
  [ "$p" = "t" ] && break; sleep 1
done
echo "  published $(( $(date +%s) - UNPAUSED ))s after unpause (1 s polling): $(outbox "$id8")"
echo "  order: $(row "$id8")"; echo "  messages for $id8: $(count_messages "$id8")"

echo "== totals $(ts)"
echo "  outbox: $(q order-service "select count(*) || ' rows, ' || count(*) filter (where published_at is null) || ' unpublished' from outbox")"
for id in $id1 $id5 $id7 $id8; do echo "  payment-confirmed messages for $id: $(count_messages "$id")"; done
echo "  relay WARN lines in order-3.log: $(grep -c 'not published, batch stopped' "$LOGDIR/order-3.log")"

stop_order; kill "$INV_PID" "$STUB_PID"; wait 2>/dev/null; echo "== processes stopped $(ts)"

```

## Day 7 end to end: consumers, expiry sweep and a real Kafka (2026-11-06)

**Label: verified against real processes and the docker-compose Kafka, on one
laptop, one run.** Every number below is from that one run (the script ran once
to completion; no earlier attempt is hidden). The gateway is the payment-stub,
not a real gateway and not the Razorpay Clone. One broker, one partition per
topic, one Inventory instance, one Order instance, no load. Timings are single
observations, not measurements.

### Setup

- Same machine and stack as Day 6: local PostgreSQL 18, Docker Desktop 29.9.2
  with **only** the Kafka service (`docker compose up -d kafka`, `healthy`).
  `inventory-service` (8082), `order-service` (8083, `--kafka.create-topics=true`)
  and `payment-stub` (8085) from `mvnw -DskipTests package` jars, `java -jar`.
  Env (DB credentials, `PAYMENT_WEBHOOK_SECRET`, `KAFKA_BOOTSTRAP`) exported from
  the gitignored `.env`; never printed. Order was started **first** so it created
  the topics, then Inventory.
- `scripts/seed/seed.sh` first. Start stock: item 1 (Runner Shoe, 4999.00)
  500, item 2 (Court Tee, 799.00) 50, both at version 0. Single-item orders
  buy product 2 x 1 (848.00 with the fee).
- Defaults otherwise: sweep every 30 s, batch 100, relay poll 1 s. For scenarios
  C and F **only**, Order was restarted with `--order.payment-window-ms=20000`
  (a command-line override, not committed). Scenarios A, B, D, E and G ran with
  the 360 s default window.
- The jar included the same day's drain loop (a sweep tick keeps going while a
  pass picks a full batch). It was not exercised here: no pass ever picked 100
  orders.

### Results

| # | Scenario | Observed |
|---|---|---|
| 0 | Inventory start-up | The `payment-confirmed` topic still held **15 messages from the Day 6 run** (end offset 15). Inventory reads a new group from `earliest`, so it consumed them all and logged **15 ERRORs**, "PaymentConfirmed for order 16997 ... Inventory holds NO reservation for it ... Nothing changed" (those orders do not exist in the freshly seeded database). Stock unchanged: 500/v0, 50/v0. |
| A | Failed payment releases stock | Order 44448, stock 49/v1 after reserving. Stub `FAILED` -> order `PAYMENT_FAILED` 0.1 s later -> `OrderCancelled {reason: PAYMENT_FAILED}` published 1.3 s later (`attempts` 0) -> reservation `RELEASED` 0.2 s after that. Webhook to released: **1.9 s**. Stock back to 50, **v2** (one reserve + one add-back). |
| B | Paid | Order 44449, `PAID` 0.3 s after the webhook, `PaymentConfirmed` published 0.6 s later, reservation `CONFIRMED` 0.2 s after. Webhook to confirmed: 1.3 s. Stock stayed **49/v3** (the unit is sold; confirming does not touch stock). |
| C | Expiry (window 20 s) | Order 44452, `expires_at` 23:11:04.169 UTC. Became `ORDER_EXPIRED` **29.4 s after creation**, `updated_at` 23:11:13.519, i.e. **9.35 s after `expires_at`**. Sweep log: `1 pass(es), 1 due, 1 expired, 0 failed`. `OrderCancelled {reason: ORDER_EXPIRED}` published 0.1 s later, reservation `RELEASED` 0.1 s after that, stock 48/v8 -> 49/v9. The delay depends on where the 30 s tick falls: this tick fell about 30 s after Order started (up at 04:40:43.8, order expired at 04:41:13.5), so one run shows one point in the 0 to 30 s range, not a distribution. |
| D | The same `OrderCancelled` again, on the real broker | Original for order 44448 read back from the topic (1 copy), then **3 copies re-produced** to `order-cancelled` with the console producer (4 copies on the topic). Inventory logged "nothing to release" 3 times within 0.1 s. Stock and version **identical before and after: item 1 500/v0, item 2 49/v3**; the reservation stayed `RELEASED`. |
| E | Kafka paused | Order 44450 created, `docker compose pause kafka`, stub `FAILED`. Order `PAYMENT_FAILED` 0.3 s later. Sampled at +5.4, +11.0, +16.4 s: order `PAYMENT_FAILED`, outbox row **unpublished**, reservation still `RESERVED`, stock still 48/v4 (the unit not given back). Unpaused at +17.5 s: published 0.2 s later (`attempts` **2**), reservation `RELEASED` 0.2 s after that (**0.6 s from unpause**, all three waits added up). Stock 49/v5. Order logged two producer ERRORs while paused (sends at 04:40:00 and 04:40:06; the relay stops its batch at the first failure). |
| E (dup) | Duplicate seen again | The topic held **2 copies** of 44450's `OrderCancelled`, as Day 6 saw for `PaymentConfirmed` under a pause. Inventory logged one "released 1 reservation(s)" and one "nothing to release". Stock went up by exactly one (48 -> 49, v4 -> v5). |
| F | Inventory down | Orders 44453, 44454, 44455 created and reserved (stock 46/v12), then Inventory killed. Stub `FAILED` for 44453 and 44454; 44455 left to expire (20 s window). All three terminal after 27.9 s (`PAYMENT_FAILED`, `PAYMENT_FAILED`, `ORDER_EXPIRED`) and all three `OrderCancelled` rows published within 0.1 s each, while Inventory was still down: reservations `RESERVED`, stock still 46/v12. Inventory restarted: 44453 `RELEASED` **5.4 s** after the start-up check passed, 44454 and 44455 0.2 s after that. Stock **49/v15**: +3 units, version +3, exactly one "released" log line per order. |
| G | Multi-item order | Order 44451: product 1 x 2 + product 2 x 1 (10846.00). Reserved: item 1 x2, item 2 x1 (stock 498/v1, 48/v6). Stub `FAILED` -> `PAYMENT_FAILED`; both reservations `RELEASED` 1.0 s after the order changed state. Stock item 1 500/v2, item 2 49/v7. |

End of run: item 1 500/v2 (back to start; version 2 = one reserve + one release),
item 2 **49**/v15 (50 minus the one paid unit of scenario B). 0 unpublished
outbox rows. No ERROR in Inventory after the 15 start-up ones; Order's only
ERRORs were the two producer errors in E.

### What it shows

- The loop closes on real processes: payment failure and expiry each reached
  Inventory through Order's outbox and the broker and gave the stock back, and
  payment success confirmed the reservation without touching stock.
- A duplicate `OrderCancelled` (3 hand-produced copies in D, 1 broker-made copy
  in E) added no stock. In D the item's version did not move either, so the
  guarded `UPDATE` matched no rows and nothing was written.
- Events outlived both a paused broker (E) and a stopped Inventory (F): nothing
  was lost, and stock came back once per order. In F the three messages waited
  on the topic until Inventory returned.
- Expiry fell inside window + sweep interval (29.4 s after creation against a
  20 s window + 30 s tick), for this one order.

### Surprises

- **Start-up backlog.** A new Inventory group reads every message still on the
  topic. The 15 Day 6 messages produced 15 "needs a human" ERRORs, because their
  orders do not exist in the freshly seeded database. Harmless here, but after a
  reseed on a topic with old messages it is noisy, and it looks exactly like a
  genuinely lost reservation in the log. Not changed.
- In F the first order took 5.4 s to be released after Inventory came up, the
  other two 0.2 s. The first probably includes the consumer joining its group;
  not isolated, one observation.

### Not covered

- A crash between the database commit and the offset commit: nothing was killed
  in that window (F kills Inventory between messages, not mid-transaction).
  Duplicate handling is shown by D and E, not by an actual crash.
- Several Inventory or Order instances, more than one partition, a consumer
  rebalance, the drain loop on a real backlog, load, a malformed message on the
  real broker (tested only against the embedded broker), a real gateway or the
  Razorpay Clone. Nothing here is exactly-once: duplicates are expected and
  were seen.
- The mid-loop reservation failure variant of G (a multi-item order failing
  while still in the reserve loop); G used a payment failure.

### Left behind after the run

The databases were reseeded (`seed.sh`) and the Kafka container stopped, not
removed: `payment-confirmed` and `order-cancelled` keep this run's messages (and
the Day 6 ones), so the next Inventory start reads them all again.

### The script

Run from the repo root in Git Bash, after `docker compose up -d kafka` is
healthy and the three jars are built. It starts and stops its own processes
(the short payment window for C and F is a command-line override).

```bash
#!/usr/bin/env bash
# Day 7 end-to-end run: real inventory (8082), order (8083) and payment-stub
# (8085) processes, local Postgres, the docker-compose Kafka. One laptop.
#
# Run from the repo root, in Git Bash, after:
#   docker compose up -d kafka            (and wait for "healthy")
#   (cd order-service && ./mvnw -q -DskipTests package)   # same for inventory-service, payment-stub
# Reads DB_USERNAME, DB_PASSWORD, PAYMENT_WEBHOOK_SECRET from ./.env (never
# printed). LOGDIR (default $TMP/flashmart-e2e7, outside the repo) gets the logs.
set -uo pipefail
set -a; . ./.env; set +a
export KAFKA_BOOTSTRAP=localhost:9092
export PGPASSWORD="$DB_PASSWORD"
LOGDIR="${LOGDIR:-${TMP:-/tmp}/flashmart-e2e7}"; mkdir -p "$LOGDIR"; rm -f "$LOGDIR"/*.log

ORDER=http://localhost:8083
STUB=http://127.0.0.1:8085
SALE=1; P1=1; P2=2     # product 1 = Runner Shoe 4999.00 (item 1, 500 units); product 2 = Court Tee 799.00 (item 2, 50 units)

kx() { MSYS_NO_PATHCONV=1 docker compose exec -T kafka "$@"; }
q() { psql -w -h localhost -U "$DB_USERNAME" -d "$1" -Atc "$2"; }
ts() { date +%H:%M:%S.%3N; }
now_s() { date +%s.%N; }
elapsed() { awk -v a="$1" -v b="$(now_s)" 'BEGIN{printf "%.1f", b-a}'; }
wait_up() { for _ in $(seq 1 120); do [ "$(curl -s -o /dev/null -w '%{http_code}' "$1")" != "000" ] && return 0; sleep 1; done; echo "!! $1 never came up"; exit 1; }

start_order() { # start_order <log> [extra args]
  local log="$1"; shift
  java -jar order-service/target/order-service-0.0.1-SNAPSHOT.jar --spring.jpa.show-sql=false \
    --kafka.create-topics=true "$@" > "$LOGDIR/$log" 2>&1 &
  ORDER_PID=$!; wait_up "$ORDER/actuator/health"; echo "  [$(ts) order up, pid $ORDER_PID, $*]"
}
stop_order() { kill "$ORDER_PID"; wait "$ORDER_PID" 2>/dev/null; echo "  [$(ts) order stopped]"; }
start_inventory() { # start_inventory <log>
  java -jar inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar --spring.jpa.show-sql=false > "$LOGDIR/$1" 2>&1 &
  INV_PID=$!; wait_up http://localhost:8082/api/reservations; echo "  [$(ts) inventory up, pid $INV_PID]"
}
stop_inventory() { kill "$INV_PID"; wait "$INV_PID" 2>/dev/null; echo "  [$(ts) inventory stopped]"; }

create() { # create <buyer> <json items> -> prints the order id
  LAST=$(curl -s -w ' HTTP %{http_code}' -X POST "$ORDER/api/orders" -H 'Content-Type: application/json' \
    -H "X-Buyer-Id: $1" -H "Idempotency-Key: e2e7-$1" -d "{\"flashSaleId\":$SALE,\"items\":$2}")
  echo "  create buyer $1: $LAST" >&2
  q order-service "select id from orders where buyer_id = $1 order by id desc limit 1"
}
hook() { echo "  stub /admin/webhook $1 -> $(curl -s -X POST "$STUB/admin/webhook" -H 'Content-Type: application/json' -d "$1")"; }

ostatus() { q order-service "select status from orders where id = $1"; }
orow() { q order-service "select status || ' amount=' || amount || ' expires_at=' || to_char(expires_at at time zone 'UTC','HH24:MI:SS.MS') || ' updated_at=' || to_char(updated_at at time zone 'UTC','HH24:MI:SS.MS') from orders where id = $1"; }
outbox() { q order-service "select coalesce(string_agg(event_type || ' ' || payload_json::text || ' published_at=' || coalesce(to_char(published_at at time zone 'UTC','HH24:MI:SS.MS'),'NULL') || ' attempts=' || attempts, ' | '), 'none') from outbox where aggregate_id = $1"; }
unpublished() { q order-service "select count(*) from outbox where aggregate_id = $1 and published_at is null"; }
resv() { q inventory-service "select coalesce(string_agg('item' || flash_sale_item_id || 'x' || quantity || ':' || status, ' ' order by flash_sale_item_id), 'none') from stock_reservations where order_id = $1"; }
resv_count() { q inventory-service "select count(*) from stock_reservations where order_id = $1 and status = '$2'"; }
stock() { q inventory-service "select string_agg('item' || id || '=' || available_stock || '/v' || version, ' ' order by id) from flash_sale_items where id in (1,2)"; }
snap() { echo "  stock: $(stock)"; }
# wait <seconds> <description> <command that succeeds when done> : prints the elapsed time
waitfor() { local t0 end; t0=$(now_s); end=$(( $(date +%s) + $1 )); shift; local what="$1"; shift
  until "$@"; do [ "$(date +%s)" -ge "$end" ] && { echo "  !! TIMEOUT waiting for $what"; return 1; }; sleep 0.25; done
  echo "  -> $what after $(elapsed "$t0") s"; }
is_status() { [ "$(ostatus "$1")" = "$2" ]; }
is_published() { [ "$(unpublished "$1")" = "0" ]; }
all_resv() { [ "$(resv_count "$1" "$2")" -ge "$3" ]; }
ilog() { grep -c "$1" "$LOGDIR/inventory.log" 2>/dev/null || true; }
# Messages on a topic with this key (inside the container).
topic_msgs() { # topic key
  kx /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --topic "$1" --from-beginning \
    --formatter-property print.key=true --formatter-property key.separator='|' --timeout-ms 6000 2>/dev/null | grep "^$2|"; }

echo "== setup $(ts)"
bash scripts/seed/seed.sh > /dev/null && echo "  seeded"
echo "  start stock: $(stock)"
echo "  payment-confirmed end offset before the run: $(kx /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:29092 --topic payment-confirmed | tail -1)"
java -jar payment-stub/target/payment-stub-0.0.1-SNAPSHOT.jar > "$LOGDIR/stub.log" 2>&1 &
STUB_PID=$!; wait_up "$STUB/admin/mode"
start_order order-1.log
echo "  topics: $(kx /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --list | tr '\n' ' ')"
start_inventory inventory.log
sleep 8
echo "  inventory consumed the topic backlog from earlier runs: ERROR lines in its log: $(grep -c ' ERROR ' "$LOGDIR/inventory.log"), 'no reservation' lines: $(ilog 'NO reservation')"
START="$(stock)"; echo "  stock after the backlog: $START"

echo; echo "== A) failed payment releases stock $(ts)"
idA=$(create 301 "[{\"productId\":$P2,\"quantity\":1}]"); echo "  order $idA: $(orow "$idA")"; echo "  reservations: $(resv "$idA")"; snap
T=$(now_s); hook "{\"order_id\":$idA,\"status\":\"FAILED\"}"
waitfor 30 "order PAYMENT_FAILED" is_status "$idA" PAYMENT_FAILED
waitfor 30 "OrderCancelled published" is_published "$idA"
waitfor 30 "reservation RELEASED" all_resv "$idA" RELEASED 1
echo "  webhook to RELEASED: $(elapsed "$T") s"
echo "  order: $(orow "$idA")"; echo "  outbox: $(outbox "$idA")"; echo "  reservations: $(resv "$idA")"; snap

echo; echo "== B) paid: reservation CONFIRMED, stock stays down $(ts)"
idB=$(create 302 "[{\"productId\":$P2,\"quantity\":1}]"); echo "  order $idB: $(orow "$idB")"; snap
T=$(now_s); hook "{\"order_id\":$idB}"
waitfor 30 "order PAID" is_status "$idB" PAID
waitfor 30 "PaymentConfirmed published" is_published "$idB"
waitfor 30 "reservation CONFIRMED" all_resv "$idB" CONFIRMED 1
echo "  webhook to CONFIRMED: $(elapsed "$T") s"
echo "  order: $(orow "$idB")"; echo "  outbox: $(outbox "$idB" | cut -c1-230)"; echo "  reservations: $(resv "$idB")"; snap

echo; echo "== D) the SAME OrderCancelled again, on the real broker $(ts)"
MSG=$(topic_msgs order-cancelled "$idA" | head -1); echo "  original message on the topic: $MSG"
echo "  copies on the topic before: $(topic_msgs order-cancelled "$idA" | wc -l | tr -d ' ')"
BEFORE_STOCK=$(stock); BEFORE_LOG=$(ilog "order $idA: nothing to release"); echo "  before: $BEFORE_STOCK ; reservations: $(resv "$idA"); 'nothing to release' log lines for it: $BEFORE_LOG"
for i in 1 2 3; do echo "$MSG" | kx /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 --topic order-cancelled --property parse.key=true --property 'key.separator=|' 2>/dev/null; done
echo "  re-produced 3 copies; copies on the topic now: $(topic_msgs order-cancelled "$idA" | wc -l | tr -d ' ')"
waitfor 30 "inventory logged 3 duplicates" bash -c "[ \"\$(grep -c 'order $idA: nothing to release' '$LOGDIR/inventory.log')\" -ge $((BEFORE_LOG + 3)) ]"
echo "  after:  $(stock) ; reservations: $(resv "$idA"); 'nothing to release' log lines: $(ilog "order $idA: nothing to release")"
[ "$(stock)" = "$BEFORE_STOCK" ] && echo "  stock and version UNCHANGED" || echo "  !! stock or version CHANGED"

echo; echo "== E) Kafka paused: the event waits, then is published and released $(ts)"
idE=$(create 303 "[{\"productId\":$P2,\"quantity\":1}]"); echo "  order $idE"; snap
docker compose pause kafka > /dev/null 2>&1; PAUSED=$(now_s); echo "  [$(ts) kafka paused]"
hook "{\"order_id\":$idE,\"status\":\"FAILED\"}"
waitfor 20 "order PAYMENT_FAILED" is_status "$idE" PAYMENT_FAILED
for i in 1 2 3; do sleep 5; echo "  +$(elapsed "$PAUSED") s order=$(ostatus "$idE") unpublished=$(unpublished "$idE") reservations: $(resv "$idE") stock: $(stock)"; done
docker compose unpause kafka > /dev/null 2>&1; UNP=$(now_s); echo "  [$(ts) kafka unpaused, $(elapsed "$PAUSED") s after the pause]"
waitfor 60 "OrderCancelled published" is_published "$idE"
waitfor 60 "reservation RELEASED" all_resv "$idE" RELEASED 1
echo "  unpause to RELEASED: $(elapsed "$UNP") s"
echo "  outbox: $(outbox "$idE")"; echo "  reservations: $(resv "$idE")"; snap
echo "  copies of the event on the topic for $idE: $(topic_msgs order-cancelled "$idE" | wc -l | tr -d ' ')"
echo "  inventory log lines for order $idE: $(grep "order $idE:" "$LOGDIR/inventory.log" | sed 's/.*: OrderCancelled/OrderCancelled/' | tr '\n' ';')"

echo; echo "== G) multi-item order, payment fails: every item released $(ts)"
idG=$(create 304 "[{\"productId\":$P1,\"quantity\":2},{\"productId\":$P2,\"quantity\":1}]"); echo "  order $idG: $(orow "$idG")"; echo "  reservations: $(resv "$idG")"; snap
hook "{\"order_id\":$idG,\"status\":\"FAILED\"}"
waitfor 30 "order PAYMENT_FAILED" is_status "$idG" PAYMENT_FAILED
waitfor 30 "both reservations RELEASED" all_resv "$idG" RELEASED 2
echo "  outbox: $(outbox "$idG")"; echo "  reservations: $(resv "$idG")"; snap

echo; echo "== restart Order with a SHORT payment window (20 s), for C and F only $(ts)"
stop_order
start_order order-2-short-window.log --order.payment-window-ms=20000

echo; echo "== C) expiry: create, never pay $(ts)"
idC=$(create 305 "[{\"productId\":$P2,\"quantity\":1}]"); echo "  order $idC: $(orow "$idC")"; snap
T=$(now_s); echo "  created at $(ts)"
waitfor 120 "order ORDER_EXPIRED" is_status "$idC" ORDER_EXPIRED
echo "  seconds since the order was created: $(elapsed "$T")  (window 20 s + sweep interval 30 s = at most about 50 s)"
echo "  order: $(orow "$idC")"
waitfor 30 "OrderCancelled published" is_published "$idC"
waitfor 30 "reservation RELEASED" all_resv "$idC" RELEASED 1
echo "  outbox: $(outbox "$idC")"; echo "  reservations: $(resv "$idC")"; snap
echo "  expiry delay = updated_at - expires_at: $(q order-service "select round(extract(epoch from updated_at - expires_at)::numeric, 2) from orders where id = $idC") s"
echo "  sweep log: $(grep -h 'expiry sweep' "$LOGDIR/order-2-short-window.log" | sed 's/.*expiry sweep/expiry sweep/' | tr '\n' ';')"

echo; echo "== F) Inventory down: 2 failed + 1 expired, then Inventory starts $(ts)"
id1=$(create 306 "[{\"productId\":$P2,\"quantity\":1}]"); id2=$(create 307 "[{\"productId\":$P2,\"quantity\":1}]"); id3=$(create 308 "[{\"productId\":$P2,\"quantity\":1}]")
echo "  orders $id1 $id2 $id3 created"; F_BEFORE="$(stock)"; echo "  stock with all three reserved: $F_BEFORE"
stop_inventory
hook "{\"order_id\":$id1,\"status\":\"FAILED\"}"; hook "{\"order_id\":$id2,\"status\":\"FAILED\"}"
waitfor 120 "all three terminal" bash -c "[ \"\$(psql -w -h localhost -U '$DB_USERNAME' -d order-service -Atc \"select count(*) from orders where id in ($id1,$id2,$id3) and status in ('PAYMENT_FAILED','ORDER_EXPIRED')\")\" = 3 ]"
for id in $id1 $id2 $id3; do waitfor 30 "$id published" is_published "$id"; done
echo "  statuses: $(for id in $id1 $id2 $id3; do echo -n "$id=$(ostatus "$id") "; done)"
echo "  Inventory is DOWN. reservations: $(for id in $id1 $id2 $id3; do echo -n "$id:$(resv "$id") "; done)"; snap
start_inventory inventory-2.log
for id in $id1 $id2 $id3; do waitfor 60 "$id RELEASED" all_resv "$id" RELEASED 1; done
sleep 3
echo "  reservations: $(for id in $id1 $id2 $id3; do echo -n "$id:$(resv "$id") "; done)"; snap
echo "  stock before the outage's releases: $F_BEFORE"
echo "  inventory-2 log, per order: $(for id in $id1 $id2 $id3; do echo -n "$id x$(grep -c "order $id: released" "$LOGDIR/inventory-2.log") "; done)"

echo; echo "== end state $(ts)"
snap; echo "  start was: $START"
echo "  ERROR lines in inventory logs (after the backlog): $(grep -c ' ERROR ' "$LOGDIR/inventory.log") / $(grep -c ' ERROR ' "$LOGDIR/inventory-2.log")"
echo "  unpublished outbox rows: $(q order-service "select count(*) from outbox where published_at is null")"

echo; echo "== stopping $(ts)"
stop_order; stop_inventory; kill "$STUB_PID"; wait "$STUB_PID" 2>/dev/null
echo "done"
```

## Day 8 planned proofs (original plan; most are now built, see the sections below)

**Label: this is the PLAN as written before Day 8.** Most proofs now exist as tests
(Identity, gateway, Order, rate limiter, browse); the results are in the Day 8
sections below and `architecture.md` §8. Not every row was built as written. This is the list of what must be shown when Day 8 is built
(`decisions.md`, "Day 8: auth, gateway identity, rate limiting, browse"). No
result, count or timing exists for any line. Per `CLAUDE.md`: a guard that
carries correctness gets a break-and-restore, and a concurrency claim gets a test
that actually races.

| # | Proof (what the test does) | Expected | Kind | What it cannot see |
|---|---|---|---|---|
| 1 | Call `POST /api/orders` through the Gateway with **no token** (and again with a malformed, an expired and a `typ=brand` token) | `401` each time; Order is never called | Gateway test, real JWT filter | A token signed with a stolen secret (by design it would pass) |
| 2 | Call an `/internal/**` path (reserve, order status) **through the Gateway** | `404` (not routed); calling the service port directly still works, which is the accepted limit | Gateway route test | Reachability of `:8082` / `:8083` from outside the host |
| 3 | Hammer `/api/orders/**` as one buyer faster than 5/s, burst 10 | First ~10 pass, then `429`; a second buyer at the same moment is not limited; the 429s never reach Order | Gateway + real Redis (compose Redis, first use) | Redis down: the fail-open claim needs its own run (stop Redis, expect requests to pass), not assumed |
| 4 | **Two simultaneous signups with the same email** (two threads, a barrier, same normalised email; also one with `A@x.com` and one with `a@x.com `) | Exactly one `201`; the other `409` "email already taken"; never a `500`; one row | Concurrent test on real Postgres, `uk_users_email` | A single-threaded run; it must actually race. Break-and-restore: drop the `23505` catch and see the `500` |
| 5 | **Refresh token reuse**: refresh once (new pair), then present the *old* token again | Second call `401`; **all** that user's refresh tokens are now revoked, including the new one | Real Postgres | The two-tab case is the accepted cost here, not a bug to fix |
| 6 | **Refresh CAS race**: two threads refresh the same token at once | Exactly one gets a new pair; the loser gets `401`; the table shows one new live token or, if the loser's reuse path ran, none (state which, then assert it) | Concurrent test, real Postgres. Break-and-restore: remove `revoked_at IS NULL` from the `UPDATE` and see two winners | Behaviour across several Identity instances |
| 7 | **Forged or stale signed header**: call Order directly with (a) no signature, (b) a wrong signature, (c) a valid signature over a timestamp older than 60 s, (d) a valid signature but a client-chosen `X-Buyer-Id` that differs from the signed one; also a Gateway call with a client-supplied `X-Buyer-Id` | (a) to (d) `401`; the Gateway strips the client header so Order sees only the JWT's buyer id | Order web slice plus one Gateway-to-Order run | Replay inside the 60 s window (accepted, not tested away); a blank-secret start-up refusal needs its own test in Gateway and Order |
| 8 | **Browse page edges**: 0 sales; exactly 10 (`hasNext` false); 11 (`hasNext` true, page 1 holds 1); an ended sale and a cancelled sale are hidden; a scheduled and a live sale are shown; ordering `start_time, id` with a tie on `start_time`; page `-1` and `abc` give `400 INVALID_REQUEST`; items arrive in one extra query | As listed | Real Postgres, database clock. Assert the query count (no N+1) | Cache behaviour (there is none by design); the skipped-sale effect when a sale ends between pages (documented, accepted) |

Also required by the design, so listed here once: a start-up test in each of
Identity, Gateway and Order that a blank `JWT_SECRET` / `GATEWAY_SIGNING_SECRET`
refuses to start (the same shape as the existing `PAYMENT_WEBHOOK_SECRET` tests).

**Day 9 trap (from the design):** the oversell load test (5,000 requests, 500
units) must use many different buyers or a raised limit, or it will mostly measure
`429`s from proof 3's limiter. Seeded users cannot log in (unusable password
hashes), so the test needs real signups or a seed with real bcrypt hashes; not
decided.

## Day 8 Identity: built and tested, and what was not tested

**Label: implemented and tested at MockMvc level on real Postgres, on branch
`feat/day8-identity-auth` (not merged).** The Identity suite ran **116** tests,
all passing, in a Claude Code session after signup, login, refresh/logout and V8
were built, and **117** after a start-up test for a space-only `JWT_SECRET` was
added (same session; 0 failures, 0 errors, 0 skipped). Identity was 47 before
Day 8. The other services' counts were not re-run. No HTTP process, no gateway,
no load. Break-and-restore was done on the signup `23505` mapping, the refresh
CAS conditions, the reuse revoke-all (including its commit) and the start-up
secret check; the blank-secret check was only partly covered until the 117th
test.

**NOT tested:**
- A real HTTP process or the gateway: only MockMvc and direct service calls ran;
  tokens were verified in tests with jjwt, not by a gateway.
- Login timing. An unknown email does the same bcrypt work as a known one (a
  test checks the call), but the response times were **not measured**, and bcrypt
  milliseconds per hash at cost 10 on this laptop are **unmeasured**.
- Non-ASCII emails: Java's `toLowerCase(Locale.ROOT)` and Postgres's `lower()` may
  disagree for some characters. That would break `ck_users_email_normalized` on
  insert and surface as a `500` (not a `409`). Not exercised.
- Several Identity instances racing on one database (the races used two threads
  in one JVM).
- Start-up through the real `JWT_SECRET` environment variable: tests set the
  `identity.jwt.secret` property directly.
- Expired-row cleanup of `refresh_tokens` (designed only, no job).

## Day 8 gateway and Eureka: smoke run on real processes (2026-11-08)

**Label: run once (run 3 below), on one laptop, in a Claude Code session.** Real
processes: `eureka-server`, Identity, Inventory, Order, `api-gateway`, all from
their jars. Not load-tested, no Kafka, no payment-stub, one instance of each
service. Every number below is one measurement from that one run, not an
average. Branch `feat/day8-gateway-eureka`.

### Setup

- Jars built with `./mvnw -DskipTests package` in each module.
- Start order as designed (`development.md`): Eureka, then Identity / Inventory
  / Order, then the Gateway.
- Order started with `--outbox.relay.enabled=false --kafka.create-topics=false
  --order.sweep.enabled=false`, Inventory with `--kafka.consumers-enabled=false`
  (and the actuator `shutdown` endpoint exposed, for the graceful stop). No
  broker needed.
- `JWT_SECRET` and `GATEWAY_SIGNING_SECRET`: random per run, exported only to
  the processes. DB credentials and `PAYMENT_WEBHOOK_SECRET` from `.env`.
- Eureka timings: the library defaults, nothing set (`decisions.md`, "Day 8",
  addendum "Gateway and discovery", point 4).
- "Hard kill" is `taskkill /F` on the `java.exe` PID (TerminateProcess: no
  shutdown hook, so no deregistration). Each hard kill was confirmed by the port
  closing.

### Results (run 3)

| Step | Result |
|---|---|
| Registry after start | `IDENTITY-SERVICE`, `INVENTORY-SERVICE`, `ORDER-SERVICE` listed, 71.9 s after the three were launched (they took 42.0 s to open their ports on this laptop) |
| Signup, then login, through `:8080` | `201` (user id 2031), then `200`; first try, 17.9 s after the Gateway was launched |
| `POST /api/orders` through `:8080` with the login token, the client ALSO sending `X-Buyer-Id: 1` | Reached Order: `409 RESERVATION_FAILED` (`ITEM_NOT_IN_SALE`; sale 999999 does not exist, on purpose). The row `orders.id=75366` has `buyer_id=2031`: the signed-up user, not the forged 1 |
| `POST :8083/api/orders` directly, bare `X-Buyer-Id` | `401 {"code":"UNAUTHORIZED","message":"missing gateway identity headers"}` |
| `GET /api/flash-sales` through `:8080`, Inventory up | `404` from Inventory itself (browse is not built), so the route reached it |
| **Graceful stop** of Inventory (actuator shutdown) | Process gone after 3.0 s; **gone from Eureka's `/eureka/apps` after 7.8 s**; the Gateway answered `503 SERVICE_UNAVAILABLE` from 8.0 s |
| Inventory restarted | Listed again after 29.6 s; the Gateway routed to it again after 46.2 s |
| **Hard kill** of Inventory | Port closed after 3.6 s; **stayed listed in Eureka for 224.3 s** (3 min 44 s) after the kill |
| No Inventory instance: `GET /api/flash-sales` through `:8080` | `503 {"code":"SERVICE_UNAVAILABLE","message":"no instance of the target service is available"}` |
| **Eureka hard-killed** while everything else runs | 7.7 s later: login through `:8080` `200`; `POST /api/orders` reached Order (`503 INVENTORY_UNAVAILABLE` from Order itself, because Inventory was still dead); 68.4 s later (past one 30 s fetch and one 35 s LoadBalancer cache period) login still `200` |

### What it shows

- The Gateway's JWT check and signed header work end to end on real
  processes: Order stored the buyer from the token, and a client-sent
  `X-Buyer-Id` changed nothing.
- Order refuses a direct call that carries only a bare `X-Buyer-Id`.
- A graceful stop leaves the registry in seconds (7.8 s here).
- A hard-killed instance stays listed for minutes with the default timings
  (224.3 s here). That is shorter than the "about 5.5 min worst case" estimate
  in the addendum, which assumed every timer lands at its worst point. It is
  one run, not a bound.
- With Eureka down, the Gateway keeps routing from its cached registry, at
  least for the 68 s measured.

### Surprises

- **Run 1 and run 2 failed, and run 1 found a real bug.** The services
  registered under the Windows host name `<host>.mshome.net` (the Hyper-V/ICS
  adapter, `172.20.16.1`). The Gateway's Netty DNS resolver cannot resolve that
  name, so every `lb://` call was a `500` with nothing in the log
  (`UnknownHostException`, found after adding logging). Fixed in commit
  `fix(discovery)`: the services register `127.0.0.1`
  (`eureka.instance.prefer-ip-address`), and the Gateway now logs any error it
  turns into a `500`. **The test suites could not see this:** they use static
  discovery with `127.0.0.1` URIs.
- Run 2's hard-kill and Eureka-down numbers were **invalid and are not
  reported**. `kill -9` from Git Bash killed only the MSYS wrapper; the
  `java.exe` kept running (Inventory still answered its own `404` while
  "killed"). The script now uses `taskkill /F` and checks the port closes. The
  same run's wait also outlived the 15-minute access token, so the script now
  logs in again before each later step.
- **While a hard-killed instance is still listed, the Gateway also answers
  `503 SERVICE_UNAVAILABLE` with the message "no instance of the target service
  is available"**, because a refused connection is mapped to the same `503`.
  The status is right, but the message is wrong for that case, and a client
  cannot tell "no instance" from "instance listed but dead". Not changed.

### Not covered

- Load, more than one instance of any service (the optional two-Inventory demo
  was not run), a real network between hosts, a Gateway restarted while Eureka
  is down (designed to give `503`s; not run), Eureka down for longer than 68 s.
- A hard kill was measured once; the listed time depends on where the
  heartbeat, eviction and cache timers happen to be.
- No Kafka, no payment-stub, no successful order (no sale existed). No webhook
  through the real Gateway to the real Order (the webhook pass-through is
  covered by the Gateway tests against a stub).
- Brand tokens: Identity issues none, so the `403` was tested only in the
  Gateway tests.

### Left behind after the run

Nothing: all five processes stopped, and the script deletes the smoke user
(and its refresh tokens) from Identity and its orders, items and outbox rows
from Order. Checked after the run: 0 rows left.

### The script

```bash
#!/usr/bin/env bash
# Day 8 gateway + Eureka smoke run (docs/testing.md). Real processes on one
# laptop: eureka-server, identity, inventory, order, api-gateway. No Kafka:
# Order runs with the relay and topic creation off, Inventory with its
# consumers off. Secrets: DB and webhook from the repo-root .env (never
# printed); JWT_SECRET and GATEWAY_SIGNING_SECRET are random per run.
# Jars must be built first (./mvnw -DskipTests package in each module).
set -u
ROOT="C:/Coding Shuttle/FlashMart"
OUT="${SMOKE_OUT:-$TMP/day8-smoke}"
mkdir -p "$OUT"
cd "$ROOT" || exit 1
set -a; . ./.env; set +a
export JWT_SECRET="$(python -c 'import secrets;print(secrets.token_urlsafe(48))')"
export GATEWAY_SIGNING_SECRET="$(python -c 'import secrets;print(secrets.token_urlsafe(48))')"

trap 'for n in gateway identity order inventory inventory2 eureka; do hardkill $n; done' EXIT
GW=http://127.0.0.1:8080
EU=http://127.0.0.1:8761
now() { date +%s.%N; }
since() { python -c "import sys;print(f'{float(sys.argv[2])-float(sys.argv[1]):.1f}')" "$1" "$(now)"; }
# A port is "up" if anything answers HTTP on it (netstat does not list the
# java listeners in every shell, so it is not used).
up() { [ "$(curl -s -o /dev/null -m 2 -w '%{http_code}' "http://127.0.0.1:$1/")" != 000 ]; }
log() { echo "[$(date +%H:%M:%S)] $*"; }
status() { curl -s -o "$OUT/body.txt" -w '%{http_code}' "$@"; }
listed() { curl -s -H 'Accept: application/json' "$EU/eureka/apps" | grep -o "\"instanceId\":\"$1\"" | head -1; }
jsonget() { python -c "import sys,json;print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$(cygpath -w "$OUT/body.txt")" "$1"; }
wait_port() { for _ in $(seq 1 240); do up "$1" && return 0; sleep 0.5; done; return 1; }

start() { # name jar args...
    local name=$1 jar=$2; shift 2
    java -jar "$jar" "$@" > "$OUT/$name.log" 2>&1 &
    eval "JAR_$name=$(basename "$jar")"
}
# The Windows PID of the java.exe running a jar. kill -9 from Git Bash only
# kills the MSYS wrapper and leaves java running (seen in run 2), so a hard
# kill is taskkill /F (TerminateProcess: no shutdown hooks, no deregistration).
winpid_of() { powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -like '*$1*' }).ProcessId" | tr -d '
'; }
hardkill() { local v="JAR_$1"; [ -n "${!v:-}" ] || return 0; for pid in $(winpid_of "${!v}"); do taskkill //F //PID "$pid" > /dev/null; done; return 0; }
# A fresh access token (they live 15 min, and the hard-kill wait can be longer).
fresh_token() { status -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}" > /dev/null; TOKEN=$(jsonget accessToken); }

log "start eureka-server"
start eureka eureka-server/target/eureka-server-0.0.1-SNAPSHOT.jar
wait_port 8761 || { log "eureka did not start"; exit 1; }
until [ "$(status -H 'Accept: application/json' "$EU/eureka/apps")" = 200 ]; do sleep 0.5; done
log "eureka up"

T_SVC=$(now)
start identity identity-service/target/identity-service-0.0.1-SNAPSHOT.jar
start inventory inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar --kafka.consumers-enabled=false \
    --management.endpoints.web.exposure.include=health,shutdown --management.endpoint.shutdown.access=unrestricted
start order order-service/target/order-service-0.0.1-SNAPSHOT.jar --outbox.relay.enabled=false \
    --kafka.create-topics=false --order.sweep.enabled=false
for p in 8081 8082 8083; do wait_port $p || { log "port $p did not open"; exit 1; }; done
log "identity, inventory, order listening after $(since "$T_SVC") s"
until [ -n "$(listed identity-service:8081)" ] && [ -n "$(listed inventory-service:8082)" ] && [ -n "$(listed order-service:8083)" ]; do sleep 1; done
log "RESULT registry lists all 3 services, $(since "$T_SVC") s after they were started"
curl -s -H 'Accept: application/json' "$EU/eureka/apps" > "$OUT/registry.json"
python -c "import json;a=json.load(open(r'$(cygpath -w "$OUT/registry.json")'))['applications']['application'];print('   apps:', sorted(x['name'] for x in a))"

T_GW=$(now)
start gateway api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar
wait_port 8080 || { log "gateway did not start"; exit 1; }
log "gateway listening after $(since "$T_GW") s"

EMAIL="smoke-$(date +%s)@example.com"
PASS='smoke-password-123'
until [ "$(status -X POST "$GW/api/auth/signup" -H 'Content-Type: application/json' \
        -d "{\"name\":\"Smoke\",\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")" = 201 ]; do
    [ "$(since "$T_GW" | cut -d. -f1)" -gt 120 ] && { log "signup never succeeded: $(cat "$OUT/body.txt")"; exit 1; }
    sleep 1
done
USER_ID=$(jsonget id)
log "RESULT signup through :8080 -> 201, user id $USER_ID ($(since "$T_GW") s after gateway start)"
S=$(status -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
TOKEN=$(jsonget accessToken)
log "RESULT login through :8080 -> $S"

S=$(status -X POST "$GW/api/orders" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: smoke-$(date +%s)" -H 'X-Buyer-Id: 1' \
    -d '{"flashSaleId":999999,"items":[{"productId":1,"quantity":1}]}')
log "RESULT POST /api/orders through :8080 (client also sent X-Buyer-Id: 1) -> $S $(cat "$OUT/body.txt")"
ORDER_ID=$(jsonget orderId 2>/dev/null || echo "")
if [ -n "$ORDER_ID" ]; then
    BUYER=$(PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d order-service -tAc "select buyer_id from orders where id=$ORDER_ID")
    log "RESULT orders.id=$ORDER_ID has buyer_id=$BUYER (signed-up user id $USER_ID)"
fi

S=$(status -X POST http://127.0.0.1:8083/api/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: smoke-direct' \
    -H "X-Buyer-Id: $USER_ID" -d '{"flashSaleId":999999,"items":[{"productId":1,"quantity":1}]}')
log "RESULT direct POST :8083/api/orders with a bare X-Buyer-Id -> $S $(cat "$OUT/body.txt")"

S=$(status "$GW/api/flash-sales" -H "Authorization: Bearer $TOKEN")
log "RESULT GET /api/flash-sales through :8080 with Inventory up -> $S (Inventory has no browse endpoint yet, so its own 404 proves the route reached it)"

# --- graceful stop of Inventory ---
T0=$(now)
curl -s -X POST http://127.0.0.1:8082/actuator/shutdown > /dev/null
log "inventory: graceful shutdown requested"
while up 8082; do sleep 0.2; done
log "inventory process gone after $(since "$T0") s"
until [ -z "$(listed inventory-service:8082)" ]; do sleep 0.5; done
log "RESULT graceful stop: gone from Eureka's /eureka/apps after $(since "$T0") s"
until [ "$(status "$GW/api/flash-sales" -H "Authorization: Bearer $TOKEN")" = 503 ]; do sleep 0.5; done
log "RESULT gateway answers 503 after $(since "$T0") s: $(cat "$OUT/body.txt")"

# --- restart, then hard kill ---
T0=$(now)
start inventory2 inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar --kafka.consumers-enabled=false
wait_port 8082
until [ -n "$(listed inventory-service:8082)" ]; do sleep 0.5; done
log "inventory restarted, listed again after $(since "$T0") s"
until [ "$(status "$GW/api/flash-sales" -H "Authorization: Bearer $TOKEN")" = 404 ]; do sleep 0.5; done
log "RESULT gateway routes to the restarted Inventory again after $(since "$T0") s"

T0=$(now)
hardkill inventory2
while up 8082; do sleep 0.2; done
log "inventory: HARD kill (taskkill /F); port 8082 closed after $(since "$T0") s"
SEEN=""
while [ -n "$(listed inventory-service:8082)" ]; do
    fresh_token
    S=$(status "$GW/api/flash-sales" -H "Authorization: Bearer $TOKEN")
    [ "$S" != "$SEEN" ] && { log "   while still listed, gateway answers $S $(cat "$OUT/body.txt")"; SEEN=$S; }
    [ "$(since "$T0" | cut -d. -f1)" -gt 900 ] && { log "still listed after 900 s, giving up"; break; }
    sleep 2
done
log "RESULT hard kill: inventory stayed listed in Eureka for $(since "$T0") s"
fresh_token
T1=$(now)
until [ "$(status "$GW/api/flash-sales" -H "Authorization: Bearer $TOKEN")" = 503 ] && grep -q "no instance" "$OUT/body.txt"; do sleep 1; done
log "RESULT after it left the registry, the gateway answered 503 after another $(since "$T1") s"
S=$(status "$GW/api/flash-sales" -H "Authorization: Bearer $TOKEN")
log "RESULT no Inventory instance: GET /api/flash-sales through :8080 -> $S $(cat "$OUT/body.txt")"

# --- Eureka down ---
hardkill eureka
T0=$(now)
while up 8761; do sleep 0.2; done
log "eureka: HARD kill; port 8761 closed"
fresh_token
sleep 5
S=$(status -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
log "RESULT Eureka down $(since "$T0") s: login through :8080 -> $S"
S=$(status -X POST "$GW/api/orders" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: smoke-eu-$(date +%s)" -d '{"flashSaleId":999999,"items":[{"productId":1,"quantity":1}]}')
log "RESULT Eureka down $(since "$T0") s: POST /api/orders through :8080 -> $S $(cat "$OUT/body.txt")"
sleep 60
S=$(status -X POST "$GW/api/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
log "RESULT Eureka down $(since "$T0") s (past one 30 s fetch and one 35 s cache period): login through :8080 -> $S"

# --- clean up ---
for n in gateway identity order inventory inventory2 eureka; do hardkill $n; done
PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d order-service -q -c \
  "delete from outbox where aggregate_id in (select id from orders where buyer_id=$USER_ID); delete from order_items where order_id in (select id from orders where buyer_id=$USER_ID); delete from orders where buyer_id=$USER_ID;"
PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d identity-service -q -c \
  "delete from refresh_tokens where user_id=$USER_ID; delete from users where id=$USER_ID;"
log "cleaned up: processes stopped, smoke user and orders deleted"
```

## Day 8 smoke 2: browse, order, signed webhook and the rate limiter through the Gateway (2026-11-08)

**Label: run once on one laptop (Gateway on the `fixed` profile, so no Eureka),
real processes: Identity, Inventory, Order, payment-stub, api-gateway, the compose
Redis, the seeded databases. No Kafka (Order relay off), no load.** The script
was not saved in the repo (it lived in a scratch folder); the steps are below.

| Step | Result |
|---|---|
| Signup, login via `:8080` | `201`, `200` |
| `GET /api/flash-sales?page=0` with a token | `200`: sale 1 `LIVE` (2 items) and sale 2 `SCHEDULED` (1 item); `hasNext` false; items carry exactly `id`, `productId`, `salePrice`, `inStock`; no `availableStock`/`totalStock` in the body |
| `?page=-1`; no token | `400 INVALID_REQUEST`; `401` |
| `POST /api/orders` for seeded sale 1 product 1 | `201 PENDING_PAYMENT`, amount `5048.00` |
| payment-stub auto-webhook (signed, sent to `:8080/webhooks/payment`) | the order reached `PAID` within a few seconds |
| 15 concurrent `POST /api/orders` from one buyer (nonexistent sale, so a request that passes is a `409` from Order) | **10 passed, 5 were `429`** (burst 10) |

**Surprises / limits**
- My first burst was a sequential loop and produced **0 429s**: each order call took
  about 250 ms, i.e. under 5/s, so the limiter was right not to fire. Only a
  concurrent burst exceeds the limit.
- Two copies of the script overlapped in the last run and garbled its log; the
  figures above are from the first complete burst (a second copy saw 13 passed /
  2 limited, which fits a bucket partly refilled by the other copy's calls). The
  `Retry-After` header was not captured in this run (it is covered by
  `RateLimitTest`).
- Not covered: Redis stopped during the smoke (done in `RateLimitFailOpenTest`
  instead), Eureka/`lb://` in this run (smoke 1), more than one instance, load.
- Cleanup: the smoke user was deleted by hand; the seed was re-run afterwards,
  which also emptied Order's tables.

### payment-stub test-isolation fix (2026-11-08)

**Label: implemented; `./mvnw test` for payment-stub alone run 10 times in a row, one laptop. Full output of
every run kept in a scratch folder (not in the repo). 10 of 10 passed 22/22. The flake was not reproduced in 10.**

- **What changed (test only, no stub code):** `slowBeforeCreatesNothingUntilTheDelayHasPassed` gives up after
  200 ms, but the stub thread sleeps its full 1000 ms (`SLOW_BEFORE`) and then creates payment `k1`. That ghost
  payment could land after the next test's `store.clear()` and fail it (seen earlier as `expected: <0> but was:
  <1>`, 2 of 5 runs, diagnosis recorded on `feat/config-server`). The test now polls (25 ms steps, no fixed
  sleep) until `k1` appears, so the thread is finished when the test ends. Timeout 5000 ms: about 800 ms of the
  delay is left after the client gives up, plus a wide margin. The test fails with a message if `k1` never
  appears.
- **Not claimed:** "fixed". Ten passing runs are consistent with the cause being removed; they do not prove it
  (the flake needed about 2 in 5 runs to show before, so 10 clean runs are suggestive, not a proof). The
  break-and-restore was not repeated for this change.
- **Same pattern elsewhere?** Checked the other delay test, `slowAfterCreatesThePaymentAndTheRetry...`: not
  identical. There the payment is created *before* the sleep, so nothing is created after the test's last
  assertion; the stub thread only sleeps and then writes to a closed connection. Left unchanged.
- **Full suite on this branch (off `main`), same day:** identity 117, inventory 113, order 308, notification 29,
  payment-stub 22, api-gateway 46, eureka-server 2 = 637, 0 failures (config-server does not exist on `main`).
  `COMPOSE_PROJECT_NAME=flashmart` was set for the gateway's fail-open test, as the folder is not named that
  way in every checkout.

## Day 9 Part 0 smoke: config server (2026-11-08)

**Label: run once each, one laptop, real processes. config-server against the real public GitHub repo
(`akshxnsh/flashmart-config`), then identity-service (the observed service), then one boot each of inventory,
order and api-gateway. No Eureka, no Kafka (order started with `OUTBOX_RELAY_ENABLED=false`,
`KAFKA_CREATE_TOPICS=false`), no load. Throwaway start scripts, not saved in the repo.** Observed value:
identity's `expiresIn` from `POST /api/auth/login` (seconds), driven by `identity.jwt.access-ttl`
(local file `15m` = 900).

| Step | Result |
|---|---|
| (a) repo `access-ttl: 16m`, pushed, restart identity only (config-server not restarted) | log `CONFIG SOURCE: config server`; `expiresIn` **960**. Change then reverted and the revert pushed (repo is back to `15m`). |
| (b) config-server stopped, identity restarted | log `CONFIG SOURCE: local fallback`; boots; `expiresIn` **900** |
| (c) config-server serving `15m`, identity started with env `IDENTITY_JWT_ACCESSTTL=20m` | log says config server; `expiresIn` **1200**: the env var beats the config server |
| inventory, order, api-gateway, each started once with config-server up | each logs `CONFIG SOURCE: config server`; the repo file names resolved (`/{name}/default/main` returned each file). Their values were not individually checked. |
| the same three started once with config-server down (first attempt, config-server had failed to start) | each logs `local fallback` and starts |

**Surprises**
- **config-server did not restart on Windows**: it deletes and re-clones its base directory on every start
  and the delete failed on git's read-only pack files (`AccessDeniedException`). Fixed with a fresh base
  directory per start; the restart then worked (restarted once more after the fix, served all three files).
- My first kill command matched the wrong processes and did nothing, so my first "restart" test read the old
  instance (`expiresIn` 900 again). Caught because the value did not change; redone with an exact process filter.
- Not covered: a hung config server (accepts, never answers), more than one instance, config-server
  behind a firewall/with no internet at start (the first clone would fail), the order, inventory and
  gateway values actually changing, `@Value` values of the fallback copy drifting from the repo.
- Tests: no test calls port 8888 (client switched off in each service's test config). Checked by searching
  the test output and surefire reports for `8888`: no hits. Not a network capture.

### Hung config server (2026-11-08)

**Label: run once each, one laptop, identity-service only. A throwaway Python script on `8888` accepted TCP
connections and never replied (`curl` to it timed out), config import timeouts 1000 / 3000 ms.**

| Run | Launch to "Started" line | Result |
|---|---|---|
| hung server on 8888 | **23.6 s** (Spring's own "Started in" 21.9 s) | 3 `Read timed out` (6 log lines), the fake server saw 3 connections, log `CONFIG SOURCE: local fallback`, service up |
| nothing on 8888 (refused) | **13.9 s** | no timeouts, `local fallback`, service up |

The hang added about 9.7 s, i.e. 3 attempts x 3 s. With the library defaults it would be 3 x 185 s read
timeout by the documented/bytecode values; **that was not measured** (nobody waited). Early-start log lines
are replayed with the time logging starts, so their own timestamps mislead; the fake server's accept times
were used. Not run: inventory, order and the gateway against a hung server (same property names, same
mechanism, not run), a slow-but-answering server. Nothing was left on 8888 afterwards.

### payment-stub flaky test (2026-11-08)

**Label: `./mvnw test` for payment-stub alone, run 5 times in a row, one laptop. Full output of every run
kept in a scratch folder (not in the repo). It FAILED in 2 of the 5 (runs 2 and 4); runs 1, 3, 5 passed 22/22.**

- **Failing test, both times, same line:** `PaymentStubTest.badInputIs400InvalidRequestAndCreatesNothing`,
  `PaymentStubTest.java:195`, `expected: <0> but was: <1>` (`assertEquals(0, store.size())`).
- **Cause (from the code, then checked by a throwaway experiment):** a test-isolation bug, not a stub bug.
  `slowBeforeCreatesNothingUntilTheDelayHasPassed` gives up after 200 ms, but the server thread keeps
  sleeping its 1000 ms (`SLOW_BEFORE`) and then creates payment `k1`, about 800 ms after the test has ended.
  `@BeforeEach store.clear()` of the next test has already run, so a later test sees an extra payment.
  Which test it hits depends on timing: `badInput...` runs about 0.55-0.65 s after `slowBefore...` and
  fails when the ghost lands inside its 0.09 s window. **Experiment:** with that delay temporarily cut to
  600 ms (reverted, file clean) the ghost landed in `concurrentRequestsWithTheSameKeyCreateExactlyOnePayment`
  instead and that test failed 3 of 3 runs with `expected: <50> but was: <51>`.
- **Not fixed.** Proposed fix (waiting for a decision): after its assertion, that test waits (polls with a
  timeout, no fixed sleep) until the ghost payment appears, so nothing outlives the test.
- **Earlier failures:** the first baseline run and the full-suite run after Fix 1 each had one payment-stub
  failure and 22/22 on the immediate rerun. Their output was not kept, so that they were this same test is
  consistent with the above but **not proven**.

## Day 9 Part B smoke: Prometheus, Grafana, the dashboard (2026-11-08)

**Label: run once, one laptop, real processes started from a git worktree: config-server, Eureka, identity,
inventory, order, payment-stub and the api-gateway on the jars, Kafka, Prometheus and Grafana from compose. Not a load
test (about 40 orders in all). No screenshot was taken: what is reported is the values each dashboard panel's own query
returned, run through Grafana's HTTP API (`/api/ds/query`) against the provisioned datasource.**

| Check | What was seen |
|---|---|
| Prometheus targets | all four (`api-gateway`, `identity-service`, `inventory-service`, `order-service`) `up`, scraped via `host.docker.internal` |
| Provisioning | Grafana started from files only: datasource `Prometheus` (uid `prometheus`) and dashboard "FlashMart overview" present |
| Traffic through the gateway (signup, login, browse, `POST /api/orders`) | order creates `201`; the first batch of 8 all `201` |
| Request rate panel | `POST /api/orders` and `reserve` rose from 0 to a maximum of about 0.44 req/s, last 0.28 |
| p99 panel (server side) | `POST /api/orders` p99 min 0.033 s, max 0.71 s; reserve p99 min 0.008 s, max 0.18 s |
| JVM heap panel | one series per service (about 44 to 124 MB used) |
| Hikari panel | series for identity, inventory, order; **active and pending were 0 at every sample** (idle samples, no contention seen) |
| Gateway 429 panel | non-zero (3.8 to 12.6 per minute): **caused by my own traffic script**, whose signup + login pairs exceeded the `/api/auth/**` limiter (1/s per IP); a second batch therefore had many `401`s from empty tokens. Not an order-limiter result. |
| Breaker: payment-stub stopped, 8 orders | all `503`; Order log `payment circuit breaker CLOSED -> OPEN (failure rate 50.0%, 10 calls in window, 5 failed)`; the panel's query went from `closed=1` to `open=1` (open-state samples, 5 s apart: `0000...0000111`). Not exercised: HALF_OPEN or the return to CLOSED. |
| `/actuator/env` masking | `DB_PASSWORD`, `JWT_SECRET`, `GATEWAY_SIGNING_SECRET`, `PAYMENT_WEBHOOK_SECRET` and the properties holding them are `******` on all four services; none of the four real values appears in any service's `/actuator/env` (searched by script) |
| Config source (`/actuator/env/<property>`) | `payment.read-timeout-ms` (order), `gateway-ratelimit.orders.burst` (gateway), `kafka.consumer.backoff-max-ms` (inventory), `identity.jwt.access-ttl` (identity): the winning source is `configserver:https://github.com/akshxnsh/flashmart-config/<service>.yml` in all four; the local `application.yml` is listed below it. **The value itself shows `******`** (Boot 4 masks every value), so the endpoint proves the source, not the number. |

**Surprises**
- The gateway had **no actuator dependency** at all (the brief assumed it did); it was added.
- Order's breaker was not tied to any registry, so no breaker metric existed until it was created through a
  `CircuitBreakerRegistry` (behaviour unchanged; the existing breaker tests still pass).
- Appending a line to `.env` when it had no trailing newline glued it onto the `DB_PASSWORD` line; caught because
  compose still said the new variable was missing; `.env` restored from the main folder.
- In the first full baseline in the worktree, `RateLimitFailOpenTest` failed ("service redis has no container to
  start"): compose names its project after the folder. With `COMPOSE_PROJECT_NAME=flashmart` it passes.
- Not covered: Hikari under real contention, a long run, the Gateway-side p99 (not on the dashboard by design), more
  than one instance per service.


## Day 9 Part C smoke: order load-test setup (2026-11-08)

**Label: run once, about 50 measured buyers (plus the 200-buyer warm-up), one laptop, stub gateway (payment-stub, auto
webhook SUCCESS). Real processes from `main`'s jars: config-server, Eureka, identity, inventory, order, payment-stub,
api-gateway; Redis, Kafka, Prometheus, Grafana from compose; local Postgres. Not a load test and no performance claim:
the full run is Part D.** The stack start script was throwaway (scratch folder); the steps are in `development.md`,
"Running the order load test".

**Step 0, merged `main` before any change:** signup + login via `:8080` gave `201`, `200`. One `POST /api/orders` (sale 1,
product 1) gave `201 PENDING_PAYMENT` and reached `PAID` within 4 s, with its reservation `CONFIRMED`. Grafana: after one
order the rate panels still read 0 (a counter that first appears at 1 shows no increase to `rate()`). After two more
orders, the rate was 0.08 req/s and both p99 series had values; breaker `closed`. The 3 smoke users were deleted afterwards.

| Step | Result |
|---|---|
| `create-users.sh`, 5,000 + 200, concurrency 8 | **110.9 s**, 5,200 created (all `201`) |
| same again | 110.9 s, 0 created, 5,200 skipped (`409`). It costs as long as creating them; the cause was not checked (likely the hash runs before the email check) |
| `make-tokens.sh`, 5,200 logins, concurrency 8 | **145.5 s**; token lifetime 900 s; **754 s (12.6 min) left** when it finished |
| Simulation start | budget left **265 s**: about 8 minutes passed between the token step and the run (the session paused between the two), not Gatling start-up |
| Status counts, `place-order` (50 buyers, 10 s ramp) | `{201=50}` |
| Status counts, `warmup` (200 buyers, item 2, 50 units) | `{201=50, 409=150}` |
| `place-order` response times (50 requests, Gatling) | p50 57, p95 88, p99 = max 95 ms. Too few requests to mean anything under load |
| `verify.sh` item 1 / item 2 | OK / OK: 450 and 0 available, 50 reservations each, all `CONFIRMED` |
| `check-orders.sh` item 1 / item 2 | OK / OK: 100 `PAID`, 150 `RESERVATION_FAILED`, 0 `CREATED`, 0 buyers with two live-or-paid orders, order units = Inventory units (50 = 50 each) |
| Grafana panels (each panel's own query via `/api/ds/query`, last 3 min) | order rate max 7.1 req/s, reserve max 9.1 req/s; server-side p99 max 0.53 s (orders) / 0.81 s (reserve) at the start, 0.07 s / 0.01 s by the end; breaker `closed` throughout; gateway 429: no values; Hikari active max 2 (inventory, order), pending 0 |

**Not covered:** 5,000 buyers (Part D), whether the full run fits the token budget (it has to fit in whatever is left
after the token step; the logins alone use 2.4 of the 15 min), contention of any kind, the 429 and 503 paths through
this scenario (none occurred), a run after the 6-minute payment window. The seed was reset after the run. The 5,200
test buyers stay in Identity on purpose (re-runs skip them).

**Full suite on `feat/load-test-setup`, same day, stack stopped, seed fresh:** identity 119, inventory 115, order 310,
notification 29, payment-stub 22, api-gateway 52, eureka-server 2, config-server 1 = **650, 0 failures, 0 errors**
(no service code changed in this piece; the load-tests module has no unit tests).

## Day 9 Part D: order load runs through the Gateway (2026-11-09)

**Label: run once each, one laptop (load tool, all services, Postgres, Docker on the same machine), stub gateway
(payment-stub, auto webhook SUCCESS, AFTER_REPLY), one Kafka broker, one partition per topic. Real processes from
`main`'s jars, started once in the documented order and NOT restarted between runs: R1 hit a freshly started JVM, R2
and R3 hit JVMs that had already served the earlier runs. Config values unchanged (provisional; tuning is Part F).
Every service logged `CONFIG SOURCE: config server` at start-up (api-gateway, identity, inventory, order). Gatling
3.16.0, `PlaceOrders`. Targets (zero oversell, p99 < 500 ms) are targets; the table says what was measured.**

| Run | Buyers | Ramp | `place-order` statuses | p99 Gatling, OK only (ms) | p99 Grafana, `POST /api/orders` server side, max over the run | Oversell check | Gap (Version B) |
|---|---|---|---|---|---|---|---|
| R1 | 1,000 | 10 s | 201 × 500, 409 × 500 | 4,761 (no KO) | 5.69 s | none (`verify.sh` and `check-orders.sh` OK on item 1) | 0 |
| R2 | 5,000 | 10 s | 201 × 500, 409 × 1,555, 503 × 46, **no response × 2,899** | 12,374 | 12.66 s | none | 0 |
| R3 | 5,000 | at once | 201 × 500, 409 × 872, **no response × 3,628** | 6,539 | 2.76 s | none | 0 |

**p99 < 500 ms: not met in any run.** Zero oversell held in all three: item 1 ended at `available_stock` 0 with
exactly 500 held units and 500 PAID-or-pending order units, item 2 at 0 with 50 / 50; no buyer had two live-or-paid
orders; nothing was stuck `CREATED`.

### Per run, the rest of the numbers

| | R1 | R2 | R3 |
|---|---|---|---|
| Token step (5,200 logins, concurrency 8) | 88.1 s | 91.3 s | 82.1 s |
| Token budget left at Gatling start / end | 800 / 781 s | 799 / 779 s | 809 / 795 s |
| Gatling wall time (incl. Maven start-up and warm-up) | 32 s | 31 s | 24 s |
| `place-order` total / OK / KO | 1,000 / 1,000 / 0 | 5,000 / 2,101 / 2,899 | 5,000 / 1,372 / 3,628 |
| `place-order` mean req/s (total) | 58.8 | 263.2 | 384.6 |
| `place-order` OK p50 / p95 / p99 / max (ms) | 723 / 3,320 / 4,761 / 6,220 | 3,487 / 9,989 / 12,374 / 13,510 | 4,670 / 6,260 / 6,539 / 6,950 |
| `place-order` KO (refused) p50 / p99 / max (ms) | n/a | 5 / 862 / 1,736 | 2,833 / 5,485 / 5,600 |
| Warm-up statuses | 201 × 42, 409 × 143, **503 × 15** | 201 × 50, 409 × 150 | 201 × 50, 409 × 150 |
| Warm-up p99 (ms) | 4,287 | 74 | 75 |
| Orders in Order after the run | 542 PAID, 650 RESERVATION_FAILED, 8 PAYMENT_UNAVAILABLE | 537 PAID + 13 PENDING_PAYMENT (all 550 reservations CONFIRMED after the drain), 1,705 RESERVATION_FAILED | 550 PAID, 1,022 RESERVATION_FAILED |
| Reservations after drain | item 1: 500 CONFIRMED; item 2: 42 CONFIRMED, 8 RELEASED | 500 / 50 CONFIRMED | 500 / 50 CONFIRMED |
| Compensation drained (outbox unpublished 0, `inventory-service` lag 0), wall time from the end of the checks | 35 s | 50 s | 35 s |
| Grafana: order rate max / reserve rate max (req/s) | 32.5 / 45.7 | 90.2 / 90.2 | 62.9 / 62.9 |
| Grafana: reserve p99 (Inventory, server side), max | 0.20 s | 0.11 s | 0.05 s |
| Grafana: breaker | `closed` throughout | `closed` throughout | `closed` throughout |
| Grafana: gateway 429 | no values | no values | no values |
| Grafana: Hikari active max, Order / Inventory / Identity | **10 (the whole pool)** / 6 / 1 | **10** / 4 / not read | **10** / 2 / not read |
| Grafana: Hikari **pending** max, Order / Inventory | **104** / 0 | **191** / 0 | **156** / 0 |
| Grafana: JVM heap max, gateway / order / inventory (MB) | 97 / 118 / 120 | 157 / 127 / 120 | 161 / 138 / 124 |
| Gateway log: `Error calling rate limiter lua` (`Redis command timed out`; fails open) | 55 | 918 | 14 |

### What it shows (and what is inference)

- **No oversell and no gap in any run.** The atomic conditional `UPDATE` and the one-live-order index held through
  the Gateway → Order → Inventory path. **The Version B gap count was 0 in all three runs.** That is three runs on
  one laptop where the reserve p99 never exceeded 0.2 s, far below Order's 2 s reserve timeout. It says nothing about
  a run where Inventory is slow enough to time out.
- **Order's connection pool was full in every run.** Its Hikari pool sat at 10 of 10 active with 104 to 191 threads
  waiting, while Inventory never had a waiter and its reserve p99 stayed at or under 0.2 s. So most of Order's
  server-side p99 (2.8 to 12.7 s) is time spent inside Order, and the full pool fits that. It is the strongest
  candidate for the bottleneck, but **not proven** (no profile, no pool-size experiment). Pool size is an open
  decision (Part F); nothing was tuned.
- **Most of R2 and R3 never reached FlashMart.** 2,899 (R2) and 3,628 (R3) requests failed with
  `java.net.ConnectException: Connection refused` at the Gateway's port. The Gateway kept running and answering. The
  likely cause is the listen backlog filling up on Windows, which refuses a connection when the backlog is full, while
  Gatling opens one new connection per virtual user. **Not proven**: no socket-level capture was taken. So these runs
  measure "how many of 5,000 got in" as much as latency, and the OK-only p99 is for the requests that got in.
- **The 46 R2 503s came from the Gateway, not from Order.** No Order row exists for them (Order holds exactly
  500 + 1,555 + 200 orders), and the Gateway's error handler turns a `ConnectException` to a backend, or "no instance",
  into `503 SERVICE_UNAVAILABLE` without logging. Most likely Order's port refused the Gateway's connection at peak.
  An inference, not traced.
- **R1 warm-up, 15 × 503, cold start.** All 15 happened in the first 0.4 s of traffic after Order started
  (00:19:32.59 to 00:19:33.00), with failure details `Could not write request: no suitable HttpMessageConverter found
  for request type [...ReserveRequest]` (7, `RESERVATION_FAILED`) and `[...InitiatePaymentRequest]` (8,
  `PAYMENT_UNAVAILABLE`). It happened in both Feign clients, only on the first concurrent burst, and not in R2 or R3
  (same JVM). The likely cause is a race in the Feign encoder's lazy set-up. **Not traced in the library; not fixed.**
  The 7 reserve calls were never written, so nothing was reserved for them. The 8 payment ones had already reserved
  stock, and the `OrderCancelled` consumer released it (the 8 RELEASED rows). The warm-up absorbed it, which is what a
  warm-up is for, but a real first burst after a deploy would see it.
- **The rate limiter failed open under load.** Redis commands timed out (500 ms) 918 times in R2. By design that
  lets the request through. No 429 was expected (one order per buyer) and none was seen. It is not known whether
  the Redis container, the Gateway's event loop or the laptop's CPU caused the timeouts.
- **Why the warm-up gives 201 × 50 / 409 × 150 when it does not fail:** item 2 is seeded with 50 units
  (`scripts/seed/inventory.sql`), and the warm-up sends 200 buyers for 1 unit each. The 409 body is
  `{"code":"RESERVATION_FAILED","message":"an item could not be reserved; the order was cancelled","orderId":...,
  "status":"RESERVATION_FAILED","reason":"OUT_OF_STOCK"}` (captured by hand after R1), and every warm-up 409 in R1 to R3
  has `failure_detail = product 2: OUT_OF_STOCK`.

### Changes to the tools during this piece

- `verify.sh` failed on correct data in R1 (item 2: 8 `RELEASED` rows whose stock was back, so `50 - 50 != 8`). Its
  arithmetic now subtracts only `RESERVED + CONFIRMED`; the two oversell checks are unchanged, and with no `RELEASED`
  rows the result is the same as before. Checked by breaking item 2's stock by one unit (FAIL, exit 1), then resetting.
  R1's item 2 numbers recorded at the time fit the new rule (50 - 42 = 8).
- `reset-run.sh` refuses any non-local host and any database that is not the local `order-service`; tested with two
  non-local hosts. `count-gap.sh` was tested on synthetic rows: a `RESERVED` row for a `RESERVATION_FAILED` order
  counted as gap 1 (exit 1); one for a missing order was shown as `MISSING` and not counted.

### Not covered

A second run of anything (each run once; no retries), a restart between runs, a run on separate machines, a run where
Inventory times out (so Version B never had a chance to show), more than one instance, more than one partition, the
token budget for a slower run (each run took about 30 s of the 13.5 min left), Identity's Hikari in R2 and R3 (not
read), what exactly refused the connections.

### Commands

The stack was started once (same order and secrets handling as the Day 8 script above; jars from `main`). Each run
used a throwaway driver kept in a scratch folder: `bash run.sh R1 1000 10`, `bash run.sh R2 5000 10`,
`bash run.sh R3 5000 0`. Its steps are below. The Grafana step ran every dashboard panel's own query through
`/api/ds/query`, as in the Part B smoke. Each run's output is in `load-tests/target/runs/<run>/` (git-ignored), and the
Gatling report is under `load-tests/target/gatling/`.

```bash
NAME=$1 BUYERS=$2 RAMP=$3
cd "C:/Coding Shuttle/FlashMart"; set -a; . ./.env; set +a
bash load-tests/reset-run.sh
curl -s -X POST http://127.0.0.1:8085/admin/auto-webhook -H 'Content-Type: application/json' -d '{"status":"SUCCESS"}'
bash load-tests/make-tokens.sh
(cd load-tests && ../order-service/mvnw -q gatling:test -Dgatling.simulationClass=com.flashmart.load.PlaceOrders \
   -Dbuyers="$BUYERS" -DrampSeconds="$RAMP")
bash load-tests/verify.sh; ITEM_ID=2 bash load-tests/verify.sh
bash load-tests/check-orders.sh; PRODUCT_ID=2 ITEM_ID=2 bash load-tests/check-orders.sh
# drain: poll every 5 s until both are 0
#   order-service: select count(*) from outbox where published_at is null
#   MSYS_NO_PATHCONV=1 docker exec flashmart-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
#     --bootstrap-server localhost:9092 --describe --group inventory-service   (sum of the LAG column)
bash load-tests/count-gap.sh
```

**Full suite on `feat/load-runs` after the runs, stack stopped, seed fresh:** identity 119, inventory 115, order 310,
notification 29, payment-stub 22, api-gateway 52, eureka-server 2, config-server 1 = **650, 0 failures, 0 errors**
(no service code changed).

## Day 9 Part F step 2: open-in-view off, 1 s pool wait in the deadline, R2 and R3 again (2026-11-09)

**Label: run once each, one laptop (load tool, all services, Postgres, Docker on the same machine), stub gateway
(auto webhook SUCCESS, AFTER_REPLY), one Kafka broker, one partition. Branch `fix/open-in-view-off` (decisions.md,
"Day 9, Part F"). Changes: `spring.jpa.open-in-view: false` in Order, Inventory and Identity; Order's Hikari
`connection-timeout: 1000` ms, counted in the `CREATED` deadline; a pool timeout answers `503 SERVICE_BUSY`. Pool
SIZE, every other timeout and the Redis values unchanged. The stack was restarted on this branch's jars, so R2F hit
FRESH JVMs (the Part D R2 ran on JVMs warmed by R1); R3F ran on the JVMs R2F had warmed. Every service logged
`CONFIG SOURCE: config server`; the 1 s pool timeout came from Order's local file (the config-repo commit is not
pushed) and every order's deadline was 20.5 s, so the running Order read it. Targets are targets.**

### Tests added (before the runs)

- `OpenInViewOffHttpTest`: a real HTTP `POST /api/orders` with a slow (300 ms) Inventory and a slow payment call
  (both mocks); the pool's active count during each call is 0. **Break-and-restore:** with `open-in-view: true` it
  failed ("a pooled connection was held while Inventory was called", active 1); restored, it passes.
- `OrderInsertTest`: the derivation (one item 20.5 s, was 14.5 s; `maxLines` 9, was 17; 9 lines 60.5 s), its
  inputs, the start-up rejection, and that the running pool's timeout is the one the deadline uses.
  **Break-and-restore:** dropping the pool-wait term failed 5 of 9; restored, 9 of 9 pass.
- `PoolTimeoutHttpTest` (pool of 2, both held by the test; Flyway needs two at start-up): a timeout after reserve
  answers exactly `{"code":"SERVICE_BUSY","message":"the service is busy; retry with the same Idempotency-Key"}`
  with 503, the order stays `CREATED` with its item unpriced, and the sweep then expires it with an
  `OrderCancelled{ORDER_EXPIRED}` outbox row; a timeout before the insert answers 503 and leaves no order.
  `PoolTimeoutHandlerTest`: any other failure of the same Spring types keeps the default 500.
- **Not tested:** Inventory actually releasing in that test (Inventory is a mock there; its consumer has its own
  tests), and the accepted residual case of stacked waits (a wait past the deadline, the sweep expiring the order,
  a late reserve committing, then a failed borrow before the Version A republish).

### Before and after

| | R2 before (Part D) | **R2F after** | R3 before (Part D) | **R3F after** |
|---|---|---|---|---|
| Buyers, ramp | 5,000, 10 s | 5,000, 10 s | 5,000, at once | 5,000, at once |
| JVMs | warmed by R1 | **fresh** | warmed by R1, R2 | warmed by R2F |
| `place-order` statuses | 201 × 500, 409 × 1,555, 503 × 46, refused × 2,899 | **201 × 0**, 409 × 1,234, 503 × 505, refused × 3,261 | 201 × 500, 409 × 872, refused × 3,628 | 201 × 500, 409 × 755, refused × 3,745 |
| Warm-up statuses | 201 × 50, 409 × 150 | **503 × 200** | 201 × 50, 409 × 150 | 201 × 50, 409 × 121, 503 × 29 |
| Gatling p99, answered only (ms) | 12,374 | 20,301 | 6,539 | 8,384 |
| Grafana p99 `POST /api/orders` (Order, max) | 12.66 s | 7.94 s | 2.76 s | **0.51 s** |
| Grafana p99 reserve (Inventory, max) | 0.11 s | 0.88 s | 0.05 s | 0.43 s |
| Order pool active / waiting max | 10 / 191 | 10 / 23 | 10 / 156 | 10 / 17 |
| Order borrows; mean hold; max hold | 2,896; 50.1 ms; 8.1 s | 6,801; 18.2 ms; 9.6 s | 2,212; 33.2 ms; 3.0 s | 7,288; **7.4 ms**; 4.6 s |
| Order wait for a connection, mean / max | 735 ms / 12.95 s | 54 ms / 1.21 s | 234 ms / 3.26 s | **12 ms / 0.50 s** |
| Order pool timeouts (`SERVICE_BUSY`) | 0 | 7 | 0 | 0 |
| Inventory pool active / waiting max | 4 / 0 | **10 / 177** | 2 / 0 | 5 / 0 |
| Inventory mean hold; max hold; max wait | 6.5 ms; 0.33 s; 5 ms | 29.8 ms; 3.4 s; 9.6 s | 3.4 ms; 0.1 s; 12 ms | 10.4 ms; 0.48 s; 0.44 s |
| Gateway Redis timeouts (fail open, 500 ms) | 918 | 987 | 14 | 218 |
| `verify.sh` both items (oversell) | OK | OK | OK | OK |
| `check-orders.sh` right after the run | OK | **FAIL** (1 order `CREATED`; units still being released) | OK | OK |
| Gap (`count-gap.sh`, after drain) | 0 | 0 | 0 | 0 |

Prometheus windows: counters from 30 s before Gatling started to 40 s after it ended; "max" values are the
gauges' maxima in that window (5 s scrapes). Grafana p99 is server side and not the headline.

### What it shows (and what is inference)

- **Order's pool stopped being the queue.** Same pool size: the longest wait for a connection fell from 12.95 s /
  3.26 s to 1.21 s / 0.50 s and the mean hold from 50 / 33 ms to 18 / 7.4 ms, at 2.3 to 3.3 times as many borrows
  (about 7 per create, as counted). In R3F Order's own p99 was 0.51 s. **The buyer's p99 (Gatling) was not**:
  8.4 s in R3F, with most of the 5,000 still refused at the Gateway's port, so the time is spent before Order
  (connection queueing, the Gateway, the rate limiter's Redis timeouts) as much as in it. Not traced.
- **R2F sold nothing.** The sequence, from timestamps in Order's database and log: on fresh JVMs every warm-up
  order failed at reserve (186 Inventory read timeouts at Order's 2 s limit, 12 Feign "no suitable
  HttpMessageConverter" errors, the cold-start error seen in Part D's R1), so the payment client was never used
  before the burst. In the burst its first 3 calls hit the same Feign error; with retries that was 5 failures in 8
  calls, over the breaker's 50 %, so it opened at 11:38:32. For the next 4 s every order that had reserved stock got
  "breaker is open" (262 + the 3), became `PAYMENT_UNAVAILABLE`, and released its stock; later buyers got
  `OUT_OF_STOCK`. The fresh JVMs confound the comparison with the Part D R2; why Inventory's pool filled
  (10 / 177) during a 200-request warm-up is **not proven** (with open-in-view off each reserve now borrows
  separately for its replay check, the liveness query and the reserve transaction; a cold JVM; Order no longer
  throttling Inventory through its own full pool: all candidates, none measured alone).
- **Late reservations (Version B) happened at scale in R2F, and the gap was still 0.** 235 reserve calls for item 1
  and 50 for item 2 timed out in Order and still committed in Inventory (item 1 ended with exactly 265 + 235 = 500
  reservations). All were released after the drain, because each late commit landed before Inventory processed
  that order's `OrderCancelled`. **That is timing, not a guarantee**: the gap stays open (decisions.md, Day 7,
  point 5).
- **The breaker carried over between runs.** It stayed OPEN from R2F (no payment calls in between) until R3F's
  warm-up; 29 warm-up orders were rejected in the 0.27 s of HALF_OPEN (3 calls allowed), then it closed. The
  measured part of R3F was not affected.
- **One order stayed `CREATED` after R2F** (a `SERVICE_BUSY` after its insert); `check-orders.sh` failed on it right
  after the run, as it should, and the sweep expired it later (`ORDER_EXPIRED`), as designed.
- Token steps: 99.7 s and 107.3 s; budgets about 13 min left at start. Drain within 30 s and 20 s.

### Not covered

A second run of either (no retries), R2F on warmed JVMs (the clean comparison), a run with a pre-warmed payment
client, the stacked double-wait case, separate machines, more than one instance, why Inventory's pool filled, what
refuses connections at `:8080`, why Redis times out.

### Commands

Same driver and steps as Part D (`bash run.sh R2F 5000 10`, `bash run.sh R3F 5000 0`; reset-run, make-tokens, Gatling
immediately, `verify.sh` and `check-orders.sh` on both items, drain, `count-gap.sh`, Grafana panels). The stack was
restarted once on this branch's jars for both runs. Prometheus numbers came from the Hikari counters and gauges
(`hikaricp_connections_usage_seconds_{sum,count,max}`, `_acquire_seconds_*`, `_pending`, `_active`,
`_timeout_total`) over the windows above.

**Full suite on `fix/open-in-view-off`, before the runs:** identity 119, inventory 115, order 317, notification 29,
payment-stub 22, api-gateway 52, eureka-server 2, config-server 1 = **657, 0 failures, 0 errors**.

## Day 9: stale-reservation reconciler (2026-11-09)

**Label: implemented and tested; demonstrated ONCE on real processes on one laptop with a FORCED late reservation
(one order, ordering forced by a row lock); a 50-buyer no-false-positive run once. Stub gateway, one Kafka broker,
one partition. Branch `feat/reconciler` (stacked on `fix/open-in-view-off`). How often Version B happens under real
overload is NOT measured here.**

### Tests

- `StaleReservationReconcilerTest` (real Postgres, Order mocked, 8 tests): `PAID` → `CONFIRMED`, stock stays taken;
  each of the four failure states → `RELEASED`, only that order's units come back; `CREATED`, `PENDING_PAYMENT`, an
  unknown status and `404` change nothing, each counted under its reason, and the pass goes on; an unreachable
  Order stops the pass (the next order is not asked) and the next pass handles both; a row just under the threshold
  is never asked about; 60 stale orders → 50 in the first pass, 10 in the second; a two-row order is asked once.
  **Race:** the reconciler and the consumer's `release` on the same reservation, two threads, 20 rounds: stock back
  exactly once each round. **Break-and-restore:** without `AND status = 'RESERVED'` in `release` it failed in
  round 0 (93 instead of 90: the units came back twice); restored, it passes.
- `OrderStatusClientTest` (JDK HTTP server as Order, 3 tests): a status, a 404, and as "unreachable": a 500, a
  1 s answer against a 300 ms read timeout (cut short), a body about another order, a closed port.
- `InternalOrderStatusControllerTest` (Order, real HTTP, 2 tests): all seven statuses as stored, 404 for an unknown
  id, no Gateway signature needed.
- `ActuatorNotRoutedTest` (+ its `fixed`-profile subclass): no route matches `/internal`, `/internal/orders/1/status`
  or `/internal/flash-sale-items/reserve`. **Break-and-restore:** adding `/internal/**` to the inventory route made it
  fail ("route inventory, GET /internal"); restored, it passes.

### Demo: a forced Version B, stuck, then released (real processes, run once)

Stack from this branch's jars; Inventory started with a SHORT threshold through command-line arguments only (no code
hook): `--reconciler.interval-ms=5000 --reconciler.threshold.order-payment-window-ms=0
--reconciler.threshold.order-max-created-deadline-ms=0 --reconciler.threshold.order-sweep-interval-ms=0
--reconciler.threshold.kafka-drain-margin-ms=60000` (threshold 60 s, a pass every 5 s). A first attempt never
reached FlashMart: the Gateway answered `503 "no instance of the target service is available"` 13 s after it
started (Order not yet discovered through Eureka); no order was created. The script then waited until an empty
order body came back `400` from Order, and ran:

| Time | What happened |
|---|---|
| 13:47:01 | A `psql` session takes `SELECT ... FROM flash_sale_items WHERE id = 1 FOR UPDATE` and holds it 15 s. |
| 13:47:02 | One order (`lt-buyer-00001`, product 1, quantity 1) through the Gateway. Inventory's reserve inserts its row and blocks on the item's stock `UPDATE`. |
| +2.33 s | Order's 2 s read timeout fires: `503 INVENTORY_UNAVAILABLE`, order 164567 `RESERVATION_FAILED`, `OrderCancelled` in the outbox (published). |
| 13:47:06 | Inventory's consumer: "OrderCancelled for order 164567: nothing to release". Reservations visible: 0. |
| 13:47:16 | The lock ends; the blocked reserve commits: order 164567, item 1, `RESERVED`, `reserved_at` 13:47:04. |
| 13:47:18 | `verify.sh` OK (available 499 = 500 - 1 held); `check-orders.sh` **FAIL: orders hold 0 units but Inventory holds 1**; `count-gap.sh` **gap 1** (`RESERVATION_FAILED`), exit 1. |
| 13:48:09 | Reconciler pass: "order 164567 is RESERVATION_FAILED; released 1 stale reservation(s)" (about 65 s after `reserved_at`: 60 s threshold + up to one 5 s interval). |
| 13:48:12 | `verify.sh` OK (available 500, 1 `RELEASED`); `check-orders.sh` OK; `count-gap.sh` gap 0, exit 0; `inventory_reconciler_actions_total{action="released"} 1` on Inventory and in Prometheus. |

**What it proves:** on real processes, the forced ordering produces exactly the Version B row, nothing else frees
it, and the reconciler asks Order and releases it, restoring the stock. **What it does not:** how often this happens
under real overload, a pass with many stale rows or a slow Order, more than one Inventory instance, and the real
570.5 s threshold (the demo used 60 s).

### No-false-positive run (real processes, run once, Inventory on its REAL reconciler config)

Inventory restarted with no arguments (threshold 570.5 s, a pass every 60 s; `CONFIG SOURCE: config server`). The
auto-webhook was turned OFF, so every order that got stock stayed `PENDING_PAYMENT` with its stock `RESERVED` for the
6-minute payment window: live orders, the case the reconciler must never act on. 50 buyers over 10 s after the
200-buyer warm-up.

- Gatling: `place-order` 201 × 50; warm-up 201 × 1, 409 × 13, **503 × 186** (the just-restarted Inventory again
  timed out at Order's 2 s during the warm-up, as in Part F's R2F; item 2 then showed 50 `RELEASED` rows, i.e. late
  commits released by the consumer in time).
- Right after: 51 `PENDING_PAYMENT`, 199 `RESERVATION_FAILED`; 51 `RESERVED`, 50 `RELEASED`.
- Watched every 60 s for 11 min: unchanged until 13:56, then Order's sweep expired all 51 (`ORDER_EXPIRED`) and the
  consumer released them by 13:57; at 14:01:15 (past the point where every row would have crossed 570.5 s): 101
  `RELEASED`, 0 `RESERVED`, `verify.sh` OK on both items, gap 0.
- **The reconciler logged nothing, and no reconciler counter existed** on Inventory or in Prometheus (no actions,
  no alerts).
- Because "nothing counted" would also be true if no pass ran, one synthetic stale row for an order that does not
  exist (id 999999999, `reserved_at` a day ago, stock taken by hand) was then inserted: the next pass (49 s later)
  asked the real Order, got `404`, changed nothing, logged the ERROR and counted
  `inventory_reconciler_alerts_total{reason="order_not_found"} 1`. The row was removed by hand and the unit put back
  (`verify.sh` OK, gap 0).

**What it shows:** with the derived threshold, Order's own sweep and the consumer settled every live order before
the reconciler would look, so the reconciler took no action and raised no alert on them; and the 404 path works on
real processes. **What it does not:** a live order older than the threshold (Order's sweep dead), which is covered
only by the unit test (`order_live`: nothing changed, alert).


### Commands

```bash
# unit and race tests
(cd inventory-service && ./mvnw test -Dtest='StaleReservationReconcilerTest,OrderStatusClientTest')
(cd order-service && ./mvnw test -Dtest=InternalOrderStatusControllerTest)
(cd api-gateway && ./mvnw test -Dtest='ActuatorNotRoutedTest,ActuatorNotRoutedFixedProfileTest')

# demo: start Inventory with a short threshold (arguments only), the rest as usual
java -jar inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar --reconciler.interval-ms=5000 \
  --reconciler.threshold.order-payment-window-ms=0 --reconciler.threshold.order-max-created-deadline-ms=0 \
  --reconciler.threshold.order-sweep-interval-ms=0 --reconciler.threshold.kafka-drain-margin-ms=60000
bash load-tests/reset-run.sh
# session 1: hold the item's row lock for 15 s
psql -d inventory-service -c "BEGIN; SELECT id FROM flash_sale_items WHERE id = 1 FOR UPDATE; SELECT pg_sleep(15); COMMIT;" &
# session 2, within that second: one order through the Gateway with a buyer's access token
curl -X POST http://localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: demo-version-b-1' \
  -H 'Content-Type: application/json' -d '{"flashSaleId":1,"items":[{"productId":1,"quantity":1}]}'
# after the lock ends: the stuck row, then again after about a minute
bash load-tests/verify.sh; bash load-tests/check-orders.sh; bash load-tests/count-gap.sh
curl -s http://localhost:8082/actuator/prometheus | grep ^inventory_reconciler
```

### Addendum: keyset cursor (review fix)

- `releasableRowsBehindAFullBatchOfLiveOnesAreReachedWithinTwoPasses`: 55 stale rows answered `PENDING_PAYMENT`
  and one `ORDER_EXPIRED` row behind them. **On the old code it failed** ("expected RELEASED but was RESERVED" after
  2 passes); with the cursor it passes (released in the second pass, the 55 untouched).
- `afterTheEndIsReachedTheNextPassStartsFromTheOldestAgain`: 51 live rows: 50, then 1 (end), then the oldest has
  become `ORDER_EXPIRED` and the wrapped pass (50 rows from the oldest) releases it.
- `aPoisonOrderInTheMiddleDoesNotBlockTheRowsBehindIt`: 5 orders, the middle one always unreachable: pass 1 releases
  the two before it and stops; pass 2 starts after it and releases the two behind it; pass 3 (wrapped) asks the
  poison order again and stops there. 4 of 5 units back.
- `ReconcilerCountersTest`: right after the reconciler is built, the six counters exist at 0 in the registry and no
  other meter does.
- Changed: `anUnreachableOrderStopsThePassAndIsRetriedAfterTheWrap` (was "...AndTheNextPassRetries"): the next pass
  now starts after the failed order, so it is released one pass later (after the wrap). Its old expectation failed
  on the new code (as the settled design intends). The class now also runs passes in `@BeforeEach` until one reaches
  the end, so every test starts from the oldest row (the reconciler is one bean with an in-memory cursor; one test
  that stopped early once broke the next). The race test and the other tests are unchanged.
- Full suite after the fix: identity 119, inventory 130, order 319, notification 29, payment-stub 22, eureka-server 2,
  config-server 1, api-gateway 52 with **1 failure** (`PrometheusEndpointTest`: `/actuator/health` 503). All four
  compose containers had been stopped from outside the session at 14:15:55, so Redis was down when it ran; the
  Gateway's health includes Redis. With Redis back (started by `RateLimitFailOpenTest`) the api-gateway module was
  run once more: 52, 0 failures. Total **674**.


**Full suite on `feat/reconciler`, before the demo:** identity 119, inventory 126, order 319, notification 29,
payment-stub 22, api-gateway 52, eureka-server 2, config-server 1 = **670, 0 failures, 0 errors**.

## Day 9: cold-start diagnosis, Feign converter error and Inventory's pool (2026-11-09)

Diagnosis only: no production code, config, pool or timeout changed. Two harnesses were added as `main()` classes in
test sources (no `@Test`, so the suites do not run them and the counts do not change). One laptop, a few runs each,
no load tool.

### Problem A: "no suitable HttpMessageConverter found for request type [...]" in Order

**Cause: proven (a race in the library, not in FlashMart code).** `spring-cloud-openfeign-core` 5.0.2,
`FeignHttpMessageConverters.initConvertersIfRequired()`, read in the 5.0.2 sources jar:

```java
if (this.converters == null) {
    this.converters = new ArrayList<>();   // published EMPTY, no lock
    // ... registerDefaults(), customizers, then hmc.forEach(converter -> converters.add(converter));
}
```

The first thread to call it publishes an empty list and then fills it. Any thread that arrives in that gap sees a
non-null list, skips the set-up, and loops over zero (or a half-filled list of) converters. `SpringEncoder` then
finds nothing that can write the body and throws the message we saw. The bean is defined in
`FeignClientsConfiguration`, which runs once **per Feign client** (each client has its own child context), so each
client fails on its own first burst. That fits R2F: the payment client failed on its first burst even though the
Inventory client had been used before.

**The earlier guess is ruled out for this version.** In Spring Framework 7.0.9,
`AbstractJacksonHttpMessageConverter.canWrite` checks only the media type and charset. It no longer builds a
serializer (Spring 6's `canSerialize` did, and swallowed the cause). The DEBUG run shows nothing was swallowed
(below).

Harness: `order-service/src/test/java/com/flashmart/order/diagnosis/FeignColdStartHarness.java`. It builds a Spring
context with only Order's two real Feign clients (same interfaces and per-client config classes; Jackson,
HTTP-converter and Feign auto-configuration; Order's `application.yml` is on the classpath) against a JDK HTTP stub.
It fires 50 concurrent first calls, 25 through each client, released together by a latch.

| Run | Rounds | Rounds with failures | Calls failed per round |
|---|---|---|---|
| `burst`, a fresh JVM per round | 20 | **20 / 20** | 7 to 44 of 50 |
| `warm` (one sequential call per client, then the same burst), a fresh JVM per round | 20 | **0 / 20** | 0 |
| `direct`: 50 threads race `getConverters()` on a new instance (one JVM, 20 rounds) | 20 | **20 / 20** | 49 of 50 saw **size 0**, 1 saw the full 7 |

- Failures across the 20 `burst` JVMs (number of rounds each message appeared in): "no suitable
  HttpMessageConverter" for `ReserveRequest` in 20 and for `InitiatePaymentRequest` in 18. Also
  `Index 1 out of bounds for length 0` (3), `Index 11 out of bounds for length 10` (1), and an `EncodeException` with
  a null message (1). The last three are what reading an `ArrayList` while another thread adds to it looks like: the
  same race, a different symptom. Order would treat them the same way (a failed call).
- **DEBUG run** (`org.springframework.http.converter` and `org.springframework.cloud.openfeign.support` at DEBUG, one
  burst): 33 calls failed, 17 succeeded, and there were exactly 17 "Writing [...] using [...]" DEBUG lines. The 33
  failing calls logged nothing from either package, and there were no WARN/ERROR lines. The stack ends in
  `SpringEncoder.encodeWithMessageConverter(SpringEncoder.java:168)`, the throw after the loop, with no cause attached.
- The harness's burst is harsher than production (50 threads released at the same instant), so its failure counts
  do not predict how many real orders fail. Production saw 15 (R1) and 12 + 3 (R2F).

**Versions that resolve** (Order's `dependency:tree`): spring-cloud-openfeign 5.0.2, feign-core 13.6.1, Spring
Framework 7.0.9, Spring Boot 4.1.1, and Jackson 3 (`tools.jackson`) 3.1.5 for the converters. Jackson 2.21.5 is
also on the classpath, pulled in by the Eureka client (`spring-boot-jackson2`); it plays no part in this failure.

**Upstream** (read on GitHub, not tried): fixed in spring-cloud-openfeign **5.0.3** (released 2026-08-20), PR #1384
"Fix concurrent initialization race in FeignHttpMessageConverters" (issue #1371, milestone 5.0.3). Current `main`
builds the list locally and publishes it only once complete (`volatile` field plus a double-checked lock). 5.0.4 is
the latest 5.0.x. Issue #1307 (the same race) still shows as open. Which Spring Cloud train release ships openfeign
5.0.3 was not checked. Nothing was upgraded.

**Warm-up evidence (step 4):** one sequential call per client before the burst gave 20 / 20 clean rounds. Not added
to the app.

### Problem B: Inventory's Hikari pool filled during a cold-start burst

Probe: `inventory-service/src/test/java/com/flashmart/inventory/diagnosis/ReserveColdStartProbe.java`, against a
fresh Inventory jar on `:8082`. Order was not involved. Conditions: config server and Eureka not running (local
fallback config); Postgres local; Kafka from compose. The seed was reset before each run. 200 concurrent reserves
were released by a latch, all on **one item** (sale 1, product 2 = item 2, 50 units), quantity 1, distinct order
ids, with a 30 s client timeout so latencies past Order's 2 s limit are visible. One dedicated JDBC connection sampled
`pg_stat_activity` for `inventory-service` every 250 ms; a second thread scraped Hikari from `/actuator/prometheus`.
"Warm" means 20 sequential reserves on the same item on the same fresh JVM, then the seed reset again (same stock),
then the burst.

| Run (fresh JVM each) | Outcomes | p50 / p99 / max (ms) | Calls > 2 s | Hikari max active / pending seen |
|---|---|---|---|---|
| cold1 (first sampler version) | 50 RESERVED, 150 OUT_OF_STOCK | 2,112 / 2,256 / 2,257 | **150** | 10 / 176 |
| coldA | same | 2,047 / 2,282 / 2,286 | **123** | 10 / 96 |
| coldB | same | 1,984 / 2,232 / 2,254 | **87** | 10 / 149 |
| warmA (20 sequential first) | same | 548 / 850 / 867 | 0 | 0 / 0 * |
| warmB (20 sequential first) | same | 647 / 797 / 803 | 0 | 1 / 0 * |

\* The Hikari scrape goes through Inventory's own Tomcat and queues behind the burst (scrapes took 50 ms to 1.4 s),
so these readings are coarse and short peaks can be missed. The warm "0 / 0" means "none seen", not "none".

There was also an accidental run (cold2) against cold1's JVM, which had not been stopped and was already warmed by a
full 200-call burst: p99 795 ms, 0 over 2 s. It is reported as that, not as a fresh run. The run script was then
fixed to refuse to start if anything already answers on `:8082`.

Without Order in the loop, the cold runs repeat R2F's pattern: the pool full (10 / 96 to 176 against R2F's
10 / 177) and calls past 2 s (87 to 150 against R2F's 186 timeouts).

**What the connections were doing** (sessions summed over all samples of a run: one session in one sample counts 1):

| Run | idle | idle in transaction, `Client/ClientRead` | active (running) | active, `Lock/transactionid` |
|---|---|---|---|---|
| coldA | 94 | 36 | 0 | **0** |
| coldB | 98 | 32 | 0 | **0** |
| warmA | 54 | 22 | 3 | 1 |
| warmB | 64 | 16 | 0 | 0 |

- **Row-lock queue on the hot item: not the cause, as far as 250 ms sampling can see.** No lock waits were sampled in
  either cold run. A short lock wait between samples can be missed, so this rules out a lasting lock queue, not every
  lock wait.
- **The busy connections were waiting on the JVM, not on Postgres.** Every non-idle session in the cold runs was
  `idle in transaction` with `ClientRead`: a transaction open, Postgres waiting for Inventory to send the next
  statement. The connection is held while Inventory's own code runs between statements.
- **For about 1.4 s after the release, nothing reached Postgres.** In coldA the burst was released at t=638 ms and all
  10 sessions stayed plain `idle` until about t=2,070 ms; the database work then ran from about 2.07 to 2.9 s. In
  warmA, transactions appear about 220 ms after the release and are done by about 1.4 s.
- **The first single call on a cold JVM took 1,036 ms on its own**; calls 2 to 20 took 16 to 34 ms (warmA's warm-up).

**Cause: partly proven.**
- Proven in these runs: the pool fills and calls pass 2 s only on a cold JVM. 20 sequential calls first removed it
  in 2 of 2 runs. So it is **not** the hot item's row lock and **not** pool size alone: 10 connections handled the
  same 200-call burst on the same item in under 0.9 s once warm.
- Proven: the held connections are waiting on Inventory's code (`ClientRead` inside a transaction), not on a lock.
- **Not proven: what the cold JVM is slow at.** Candidates are first-use class loading, the JIT, first-use set-up in
  Jackson / Hibernate / Spring MVC, and first-time query preparation. No profile was taken. One inference: a
  *sequential* warm-up (one thread) cured it, so creating many Tomcat threads is unlikely to be the main cost. The
  likelier cost is one-time set-up per code path.
- Not separated: how much "open-in-view off" (about 3 borrows per reserve) adds to the queue on a cold JVM, and how
  much the laptop's CPU does. Every run shared the laptop with Postgres and Kafka.

### Not covered

A profile of the cold JVM; Problem B with Order in the loop; more than two runs per arm in B; whether one sequential
call (instead of 20) is enough for Inventory; the Spring Cloud train release that carries openfeign 5.0.3. Order is
the only service with Feign clients (grep for `@FeignClient`), so the race cannot occur elsewhere today.

### Suites

Run on `fix/startup-diagnosis` for the two touched modules only (the others are unchanged): order **319**, inventory
**130**, 0 failures, 0 errors. The first Order run had 305 errors, caused by the harness itself: its nested config
class was a `@SpringBootConfiguration`, and Order's component scan picked it up from the test classpath, so its
`@EnableFeignClients` registered the Feign clients twice. The annotation was removed (the harness passes the class
to `SpringApplicationBuilder` as a plain source). One `burst` and one `warm` round were re-run afterwards with the
same result as before (43 / 50 failed; 0 / 50). The 20-round tables above were taken with the earlier version, which
differs only in that annotation.

### Commands

```bash
# Problem A (from order-service/, after ./mvnw test-compile and
#   ./mvnw dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=test)
CP="target/test-classes;target/classes;$(cat cp.txt)"
java -cp "$CP" com.flashmart.order.diagnosis.FeignColdStartHarness burst 1     # once per fresh JVM, x20
java -cp "$CP" com.flashmart.order.diagnosis.FeignColdStartHarness warm 1      # x20
java -cp "$CP" com.flashmart.order.diagnosis.FeignColdStartHarness direct 20
java -Dlogging.level.org.springframework.http.converter=DEBUG \
     -Dlogging.level.org.springframework.cloud.openfeign.support=DEBUG -cp "$CP" \
     com.flashmart.order.diagnosis.FeignColdStartHarness burst 1

# Problem B (repo root, .env exported, Kafka up, nothing on :8082). Per run: reseed, start a fresh jar, wait for UP.
bash scripts/seed/seed.sh
java -jar inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar &
CP="inventory-service/target/test-classes;inventory-service/target/classes;$(cat icp.txt)"
java -cp "$CP" com.flashmart.inventory.diagnosis.ReserveColdStartProbe warm 20 900000   # warm arm only,
bash scripts/seed/seed.sh                                                                 # then reseed
java -cp "$CP" com.flashmart.inventory.diagnosis.ReserveColdStartProbe burst 200 100000
# then stop the Inventory JVM before the next run
```

## Day 9: cold-start fixes (2026-11-09)

Branch `fix/startup-warmup`, stacked on `fix/startup-diagnosis`. One laptop, a few runs each, no load tool, stub
gateway. Decisions and trade-offs: `decisions.md`, "Day 9: cold-start fixes (Feign upgrade, Inventory warm-up
before the port)".

### Fix A: Spring Cloud 2025.1.2 → 2025.1.3 (openfeign 5.0.2 → 5.0.3)

Order's `dependency:tree` after the bump: openfeign 5.0.3, feign-core 13.6.1, Spring Framework 7.0.9 and Boot 4.1.1
(unchanged). The 5.0.3 sources jar was not available locally, so the fixed code was not read; the fix is shown by
behaviour. Same harness as the diagnosis (`FeignColdStartHarness`), 50 concurrent first calls, 25 per client:

| Run | Rounds | Rounds with failures | Calls failed |
|---|---|---|---|
| `burst`, a fresh JVM per round | 20 | **0 / 20** (was 20 / 20) | 0 of 1,000 (was 7 to 44 of 50 per round) |
| `direct` race on `getConverters()` | 20 | **0 / 20** (was 20 / 20) | every thread saw all 7 converters (was 49 of 50 seeing 0) |

No Feign warm-up was added to the app.

### Fix B: Inventory's warm-up before the port

Probe: `ReserveColdStartProbe` (as in the diagnosis), fresh jar, config server and Eureka not running, Kafka up,
seed reset before each run, 200 concurrent reserves at one item (50 units).

| Run (fresh JVM) | Warm-up | Calls > 2 s | p50 / p99 / max (ms) | Hikari max active / pending seen |
|---|---|---|---|---|
| coldA, coldB, cold1 (diagnosis) | off | 123, 87, 150 | p99 2,282, 2,232, 2,256 (p50 about 2,000 to 2,100) | 10 / 96, 10 / 149, 10 / 176 |
| warmA, warmB (diagnosis: 20 HTTP calls first) | off | 0, 0 | p50 548 / 647, p99 850 / 797 | 0 / 0, 1 / 0 (coarse) |
| **onA** | on | **0** | 1,414 / 1,617 / 1,629 | 10 / 183 |
| **onB** | on | **0** | 1,016 / 1,251 / 1,268 | 9 / 53 |
| **onC** | on | **0** | 1,132 / 1,417 / 1,433 | 10 / 180 |

- **The 2 s timeouts are gone in 3 of 3 runs. The cold cost is not all gone.** p99 is 1.25 to 1.62 s: better than
  2.23 to 2.28 s without it, worse than 0.80 to 0.85 s once HTTP calls have been served, and near Order's 2 s
  limit. The pool still fills (expected: 200 concurrent calls, 10 connections). The Hikari readings are coarse (the
  scrape queues behind the burst), so they are not comparable with the earlier "0 / 0".
- **What a before-the-port warm-up cannot warm**, seen in the first single HTTP call on a fresh JVM (3 runs each):
  without the warm-up 1,182 / 848 / 826 ms; with it 268 / 202 / 219 ms; a warm call is 16 to 34 ms. About 200 ms is
  still paid by the first call: Tomcat, the DispatcherServlet's first-request set-up, Jackson on the request and
  response, validation. Inference, not profiled.
- **Start-up time:** "Started InventoryServiceApplication in" 11.28 / 11.74 / 11.60 s without (mean 11.54), 12.49 /
  12.79 / 12.27 s with (mean 12.52): about **+1.0 s**. The warm-up line itself reports 0.78 to 1.12 s.
- **Order of events** (one start, from the log): `startup warm-up: 20 reserve calls, all rolled back, 995 ms`, then
  Eureka "Registering application", then "Tomcat started on port 8082", then the first request's DispatcherServlet
  initialisation.
- **Side effect:** each start burns 20 ids of the `stock_reservations` sequence (reservation ids jumped by about 20
  between consecutive starts).

### Tests added (7; inventory 130 → 137)

`StartupWarmupTest` (real Postgres, not `@Transactional`): the real reserve path runs 20 times and nothing of it
commits (native SQL before and after: reservation rows, reserved units, summed stock, summed versions, item count;
and the id sequence advanced by exactly 20, so "unchanged" is not "never ran"); an empty database is skipped and
`reserve` is never called; a failing reserve returns `FAILED` and does not throw; a hung reserve is cut off at the
timeout (`TIMED_OUT`, 1 s configured); disabled does nothing. `StartupWarmupBeforePortTest` (real web server, warm-up
on): a post-processor stamps every start-up reserve call and a listener stamps the web-server event; all 20 calls
come before it. `StartupWarmupFailingStartTest` (warm-up on, the reserve service replaced by one that always throws):
the context starts anyway, with the WARN "startup warm-up: failed after 0 calls; starting anyway" in the log. Both
context tests are skipped, visibly, if the database has no item at start-up; neither was skipped here.

- **Break-and-restore, once each.** (1) Removing `setRollbackOnly()`: `theRealReservePathRunsAndNothingOfItCommits`
  failed (reservation rows: 0 expected, 20 seen); restored. (2) Making the class an `ApplicationRunner`:
  `everyStartUpWarmupCallIsBeforeTheWebServerIsInitialised` failed (the last call came after the web-server event);
  restored.
- **What these tests cannot see:** a second Inventory instance, a production-size table, a database that is slow
  rather than empty. The failing-start test did not get its own break-and-restore (a failure that stops start-up
  was not injected into the class).

### Suites

Run after the last code change, in this session: identity 119, inventory 137, order 319, notification 29,
payment-stub 22, api-gateway 52, eureka-server 2, config-server 1 = **681, 0 failures, 0 errors, 0 skipped**
(674 before the 7 new tests; the harnesses have no `@Test`. The other seven modules were last run in the full run before the seventh test was added; nothing in them changed). **A first Inventory run after the bump had 6
reconciler failures**: the reconciler tests read every stale `RESERVED` row in the shared database, and the earlier
diagnosis bursts had left 50 there. After `seed.sh` the same suite passed. Reset the seed after any manual burst.

### Commands

```bash
# Fix A: the Problem A commands from the diagnosis section (after ./mvnw test-compile in order-service)
# Fix B (repo root, .env exported, Kafka up, nothing on :8082): a fresh jar per run, seed.sh first
bash scripts/seed/seed.sh
STARTUP_WARMUP_ENABLED=true java -jar inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar &   # false = off
java -cp "$CP" com.flashmart.inventory.diagnosis.ReserveColdStartProbe burst 200 100000   # or: warm 1 900000
# stop the jar before the next run
```

## Day 9: R2 re-run on the final build (2026-11-09)

**Label: ONE run, one laptop (Gatling, all services, Postgres, Docker on the same machine), stub gateway
(payment-stub, auto webhook SUCCESS, AFTER_REPLY), one Kafka broker, one partition per topic. Measure only: no code,
config, pool size or timeout was changed. p99 < 500 ms and 5,000 concurrent buyers are TARGETS.** Branch
`perf/r2-rerun`, built from `main` (merge `3f58705`). Gatling 3.16.0, `PlaceOrders`.

### Setup, and one thing found on the way

- **The jars in `*/target` were stale.** Order's jar still bundled openfeign 5.0.2 (the version with the Feign
  first-burst race) and Inventory's jar was built before the warm-up commit. The modules were rebuilt with
  `./mvnw -q -DskipTests package` before anything started (no source change; payment-stub's jar timestamp did not change).
  After the rebuild Order, Inventory and Identity bundle `spring-cloud-starter-openfeign-5.0.3`, the Gateway
  `spring-cloud-gateway-server-webflux-5.0.3`. A run on the old jars would have been invalid.
- Stack started once, in the documented order, by the throwaway `recon-tools/stack3.sh` (config server, Eureka,
  Identity/Inventory/Order/payment-stub, Gateway), all JVMs fresh. Inventory's warm-up ran before its port opened
  ("20 reserve calls, all rolled back, 2375 ms"). Reconciler ON at its real threshold (570.5 s). Compose: Redis, Kafka,
  Prometheus, Grafana.
- **Order's first config fetch timed out** ("Read timed out" on `http://localhost:8888/order-service/default`, one
  WARN at start-up) and it fetched a second time; the log does not say which source won. Not investigated. The local
  fallback copy is meant to hold the same values (drift is unchecked).

### Warm-up gate (passed, so the 5,000 ran)

`BUYERS=1 WARMUP=50 make-tokens.sh`, then `PlaceOrders -Dbuyers=1 -DwarmupBuyers=50 -DrampSeconds=1` through the
Gateway. Result: `201 x 50` (warm-up, item 2) and `201 x 1`; Order: 51 PAID, 0 failed; breaker `closed`, 51 successful
calls, 0 failed; no Feign or converter error in Order's log (its two WARNs are the config fetch above and the load
balancer cache notice); Inventory pool 10 total, 0 active, 0 pending, 10 idle afterwards.

### Reset between the gate and the run

`reset-run.sh` (the first step of `run.sh`) re-seeded both databases and proved them empty (orders, order_items,
outbox, refunds = 0; reservations = 0; all items at full stock), so the gate used no stock or tokens meant for the
run. `make-tokens.sh` then overwrote the gate's 51 tokens with 5,200 (5,000 measured + 200 warm-up), 102.0 s. Budget
left at Gatling start / end: **789 / 767 s** (no expiry). JVMs were NOT restarted between the gate and the run (the
51 gate orders had warmed them). The Gatling run itself warms item 2 with 200 buyers first, as in Part D.

### Commands

```bash
cd "C:/Coding Shuttle/FlashMart"; set -a; . ./.env; set +a          # .env has GRAFANA_ADMIN_PASSWORD
for m in config-server eureka-server identity-service inventory-service order-service payment-stub api-gateway; do
  (cd $m && ./mvnw -q -DskipTests package); done
docker compose up -d prometheus grafana                               # redis and kafka were already up
LOGDIR=logs-r2rerun bash load-tests/target/recon-tools/stack3.sh &    # throwaway, git-ignored
bash load-tests/reset-run.sh
curl -X POST http://127.0.0.1:8085/admin/auto-webhook -H 'Content-Type: application/json' -d '{"status":"SUCCESS"}'
BUYERS=1 WARMUP=50 bash load-tests/make-tokens.sh
(cd load-tests && ../order-service/mvnw -q gatling:test -Dgatling.simulationClass=com.flashmart.load.PlaceOrders \
   -Dbuyers=1 -DwarmupBuyers=50 -DrampSeconds=1)                      # the gate
bash load-tests/target/recon-tools/run.sh R2rerun-run 5000 10         # reset, auto-webhook, 5,200 tokens, Gatling, checks, drain, count-gap, Grafana
python load-tests/target/recon-tools/hikari2.py 2026-11-09 R2rerun 17:07:25 17:07:57
```

### Result (Gatling 17:07:25 to 17:07:57 IST, wall time 32 s including Maven start-up and the 200-buyer warm-up)

| | This run (R2 re-run) | Part D R2 | R2F |
|---|---|---|---|
| JVMs | fresh, plus a 51-order gate | warmed by R1 | fresh |
| Orders sold / expected (item 1) | **500 / 500** | 500 / 500 | **0** / 500 |
| `place-order` statuses | 201 x 500, 409 x 522, **no response x 3,978** | 201 x 500, 409 x 1,555, 503 x 46, no response x 2,899 | 201 x 0, 409 x 1,234, 503 x 505, no response x 3,261 |
| Warm-up statuses | 201 x 50, 409 x 150 | 201 x 50, 409 x 150 | 503 x 200 |
| Oversell (`verify.sh`, `check-orders.sh`, both items) | none | none | none (`check-orders` FAIL: 1 order `CREATED`) |
| Gap, Version B (`count-gap.sh` after drain) | 0 (drain 20 s, outbox 0, lag 0) | 0 | 0 |
| Gatling p50 / p95 / p99, answered only (ms) | 3,553 / 5,337 / 6,022 | 3,487 / 9,989 / 12,374 | 9,747 / 18,253 / 20,301 |
| Gatling p50 / p95 / p99, all 5,000 incl. refused (ms) | 943 / 4,173 / 5,349 | 53 / 6,313 / 11,798 (from the old log; not in the Part D table) | 3,410 / 15,992 / 19,288 |
| Requests per second (mean, all) | 238.1 (answered 48.7) | 263.2 | 156.3 (answered 54.3) |
| Refused at `:8080` (`ConnectException: Connection refused`) | 3,978 of 5,000 | 2,899 | 3,261 |
| Order server-side p99, `POST /api/orders` (Grafana, max of 30 s windows) | 0.90 s | 12.66 s | 7.94 s |
| Inventory server-side p99, reserve (same) | 0.32 s | 0.11 s | 0.88 s |
| Order pool: max active / waiting; connection timeouts | 3 / 0; 0 | 10 / 191; 0 | 10 / 23; 7 |
| Order borrows; mean hold; mean acquire | 6,507; 7.0 ms; 0.4 ms | 2,896; 50.1 ms; (wait mean 735 ms) | 6,801; 18.2 ms; (wait mean 54 ms) |
| Inventory pool: max active / waiting; timeouts | 2 / 0; 0 | 4 / 0 | 10 / 177 |
| Gateway Redis timeouts (fail open, 500 ms) | **28** (all in the second 17:07:55) | 918 | 987 |
| Payment breaker | `closed` all run, no transition logged | not recorded here | opened (Feign error), stayed OPEN |
| Reconciler actions / alerts | 0 confirmed, 0 released / 0 (all four reasons) | n/a (not built) | n/a (not built) |

**Against the targets, per measured number:** Gatling p99 answered-only 6,022 ms and including refused 5,349 ms:
**p99 < 500 ms not met.** Order's own server-side p99 (max of 30 s windows) 0.90 s: not met. Inventory's reserve
server-side p99 0.32 s: under 500 ms (server side, not the buyer's number). 5,000 concurrent buyers: 5,000 were
offered over 10 s, 3,978 got no response: **not reached**. Zero oversell: held in this run (one run).

### Unexplained (no causes claimed)

- **Refused connections.** 3,978 of 5,000 (79.6 %) got `Connection refused`, more than Part D R2 (2,899). Why the
  port refuses was not investigated here (partly diagnosed later: "Day 9: why buyers are refused at `:8080`" below).
- **Buyer-side p99 against server-side p99.** Answered-only p99 6.0 s while Order's server-side p99 was 0.90 s and
  Order's pool never waited. The gap is time before Order (or outside it); not traced.
- **Redis timeouts.** 28 EVALSHA calls timed out at 500 ms, all within one second near the end of the run (17:07:55).
  The limiter failed open for them. Not explained. Far fewer than Part D R2 (918) and R2F (987), but one run each.
- **Why this run sold 500 where R2F sold 0.** The two fixes target R2F's two causes; this run did not test each fix
  alone, and the gate warmed the JVMs. Not separated.
- **Order's config fetch** (above): which source its values came from was not checked.

### What this run does not show

One run. The Part D comparison is against runs on other JVM states and other builds. Nothing was tuned, so the
numbers say nothing about what a different pool or timeout would do. Not a real payment gateway. Not a 5,000-concurrent
run. Not repeated, so no variance is known.

## Day 9: why buyers are refused at `:8080` (diagnosis only, 2026-11-09)

**Question.** In the R2 re-run, 3,978 of 5,000 buyers got `Connection refused` at the Gateway's port. Order's own p99
was 0.90 s and its pool never waited, so the refusals happen before Order. Hypothesis going in (unproven): the
Gateway's listen backlog (the queue of connections that finished the TCP handshake but that the server has not picked
up yet) overflows because the Gateway is busy, and Windows refuses new connections when that queue is full.

**What was run.** Three short runs plus one mechanism probe, **one run each, one laptop** (8 logical CPUs; Gatling,
servers, Postgres and Docker on the same machine). No FlashMart code or setting was changed and the full R2 was
**not** re-run. Every run used the unchanged `PlaceOrders` scenario: 200 warm-up buyers over 5 s, then 5,000 buyers
over 10 s (`rampSeconds=10`, about 500 new connections per second), one connection and one `POST /api/orders` per
buyer. Only `-DbaseUrl` and `-DtokensDir` changed. The token dir was a copy of the R2 re-run's (now expired) tokens
with a future `expires-at.txt`, so `before()` passes. All jars were rebuilt first (`mvnw -DskipTests package` in every
module, BUILD SUCCESS; no source or pom change since the warm-up commit `4b21813`). Throwaway tools (control server,
sampler, run script) live outside the repo and are not committed.

**What was recorded.** Gatling's statuses and error text. `netstat -s -p tcp` and `-p tcpv6` before and after each
run (the 5,000 connections showed up in the IPv4 counters: `Passive Opens` +5,219 in the control). About every 250 ms
during each run: connections on the server port by state (`netstat -an`), plus the server process's CPU time.
Limits of the sampler: it ran inside Claude Code's sandbox, whose `netstat` view **hides listening sockets** but
does show connections on the port (the probe below saw 200). One sample took 30 to 480 ms in the control and up to
1,390 ms in the Gateway run, so a connection that lives 1 ms is usually missed. That does not matter for spotting a
full queue, which lasts much longer. Reactor Netty server metrics are **not exported** by the Gateway (no
`reactor_netty_*` lines in `/actuator/prometheus`), so there is no count of accepted connections.

### Step 1: the exact error and how Gatling counts it

From the R2 re-run's Gatling log (`load-tests/target/runs/R2rerun-run/gatling.log`, git-ignored):
`j.n.ConnectException: Connection refused: getsockopt`, 3,978 times, 100 % of errors. No timeouts and no resets.
Gatling counts each one as a **KO request with no HTTP status**: `PlaceOrders` records it as `none`
(`{201=500, 409=522, none=3978}`), and its response time is the time until the connect failed. Those KO times were
short (min 1 ms, p50 96 ms, max 5,148 ms), so the server's side said "no" at once rather than letting the connection
time out.

### Steps 2 to 4: results per run

| | Control (no FlashMart) | Gateway only (401 path) | Probe: slow accept |
|---|---|---|---|
| Server | JDK `HttpServer` on :9099, backlog **200** (set to match Netty's default, below), answers 200 at once | the running Gateway on :8080 (warm JVM from the R2 re-run stack, started 17:03; rest of the stack up and idle) | plain `ServerSocket` on :9098, backlog **200**, accepts at most one connection per 10 ms (about 100/s), then answers 200 |
| Path | any request -> 200 | `POST /api/orders` with an expired JWT -> `AuthFilter` answers 401 before the rate limiter, Redis or Order | any request -> 200 |
| `place-order` statuses | 200 x 5,000 | 401 x 5,000 | 200 x 998, **no response x 4,002** |
| Warm-up statuses | 200 x 200 | 401 x 200 | 200 x 200 |
| **Refused** | **0** | **0** | **4,002** (`j.n.ConnectException: Connection refused: getsockopt`, the same text as R2) |
| Gatling p50 / p99 / max, all requests (ms) | 1 / 3 / 18 | 2 / 8 / 43 | 2 / 3,078 / 3,439 (refused p99 57, max 179) |
| `Failed Connection Attempts` (IPv4) before -> after | 63,309 -> 63,311 (+2) | 63,314 -> 63,317 (+3) | 63,321 -> 67,332 (**+4,011**) |
| Listener side, highest count seen: `ESTABLISHED` / `SYN_RECEIVED` | 2 / 0 | 2 / 1 | **200** / 0 (above 150 in 31 samples) |
| Server CPU | not useful (tiny) | busiest 5 s: 5.2 s of CPU (about 1 of 8 CPUs); the 5 s on each side 3.4 s and 2.4 s | not recorded (sampled the launcher process) |
| Order touched? | n/a | no: Order's non-actuator request count 1,875 before and after | n/a |

A first control run before the sampler worked (its process id lookup failed) also gave 200 x 5,000 with 0 refused.

### Step 5: the Gateway's effective backlog

**200**, read from the libraries the Gateway runs (Netty 4.2.17.Final, Reactor Netty 1.3.7, extracted from the
Gateway's jar):
- `io.netty.util.NetUtil.SOMAXCONN`: in its bytecode the Windows branch is a fixed `200` (`128` on other systems
  unless `/proc/sys/net/core/somaxconn` or sysctl says otherwise). Printed on this machine: `NetUtil.SOMAXCONN=200`.
- A fresh `NioServerSocketChannel` reports `SO_BACKLOG=200` (option read at runtime on this machine). On Windows the
  Gateway uses NIO, because the native epoll transport in the jar is Linux-only.
- Nothing overrides it: there is no `SO_BACKLOG` anywhere in `reactor-netty-core` or `spring-boot-reactor-netty`,
  and the Gateway's code and config set no Netty server option.
- What Windows does with a requested 200 (any further cap) was **not** checked. The probe shows the queue held about
  200 here.

One more library fact, relevant to "busy": Reactor Netty's `reactor.netty.ioSelectCount` defaults to `-1`, which
means **no separate accept thread**. The server socket is served by the same worker event loops that handle requests
(`ioWorkerCount` = max(CPUs, 4) = 8 here). So if those loops are busy, accepting new connections waits too. This is
read from bytecode. Whether it happened in R2 was **not measured**.

### What the evidence says

- **Ruled out: the laptop, Windows or Gatling cannot open about 500 connections per second.** The control run, with
  the same ramp on the same machine and the same backlog of 200, refused 0.
- **Ruled out: a backlog of 200 is too small for this rate by itself.** The Gateway refused 0 when its work per
  request was tiny (a 401 from `AuthFilter`, about one CPU busy).
- **Ruled out: timeouts or resets.** The error is `ConnectException: Connection refused` and it comes back fast.
- **Supported (the mechanism, on this machine):** when a server accepts more slowly than connections arrive, this
  Windows refuses the extra connections with **exactly R2's error text**. Each refusal adds one to `Failed Connection
  Attempts` (+4,011 for 4,002 refused). The full queue shows on the server's port as about **200 `ESTABLISHED`**
  connections, **not** as `SYN_RECEIVED` (it stayed at 0). So on this Windows the sign of backlog pressure is
  "ESTABLISHED stuck near 200, plus Failed Connection Attempts rising", not "many SYN_RECEIVED".
- **Still unproven:** that the Gateway's accept queue was full during R2, and why its accept would be slow then.
  R2 was not run with this sampler, so there is no queue reading and no counter delta from it. The likely suspect is
  the event loops shared between accepting and request work, under the full stack's load on one laptop. That is a
  hypothesis, not a finding. The test that decides it is one R2-shaped run with the same sampler on `:8080` and
  `netstat -s` around it: a queue near 200 and `Failed Connection Attempts` rising by about the refused count would
  confirm it.

**Likely fix:** first confirm with a sampled R2. Then give the Gateway a separate accept thread
(`reactor.netty.ioSelectCount=1`) and/or a larger `SO_BACKLOG` through a `NettyServerCustomizer`, and find what keeps
its event loops busy.
**What it would change:** buyers who are now refused would wait in the queue or be accepted, so they turn into
(slower) answered requests. It would not make Order or Inventory faster, so the answered p99 may rise. Not measured.

## Day 9: the decisive R2 run, sampled and thread-dumped (2026-11-09)

**Label: two R2-shaped runs on one laptop, stub gateway (payment-stub, auto-webhook SUCCESS, AFTER_REPLY), one Kafka
broker. Run 1 was disturbed by my own measuring and is NOT a result. Run 2 is the one reported: ONE run. Measure only:
no code, config or setting was changed. p99 < 500 ms and 5,000 concurrent buyers are TARGETS.** Branch
`perf/refused-diagnosis`.

**Question.** During an R2-shaped run, does the Gateway's accept queue really fill, and what are its event-loop
threads doing?

### Setup

- All jars rebuilt (`./mvnw -q -DskipTests package` in the seven modules, all exit 0). The stack from the earlier
  R2 re-run was stopped, then started fresh in the documented order by `recon-tools/stack3.sh` (config 18:24:59,
  Eureka 18:25:11, services 18:25:57, Gateway 18:26:12). Inventory warm-up ran ("20 reserve calls, all rolled back,
  2078 ms"). Reconciler ON at its real threshold. Redis, Kafka, Prometheus and Grafana from compose.
- Warm-up gate (`BUYERS=1 WARMUP=50`, as in the R2 re-run): `201 x 50` and `201 x 1`. Order 51 PAID, breaker
  `closed` with 51 successful calls and 0 failed, no Feign or converter error in Order's log.
- Each run: `recon-tools/run.sh NAME 5000 10` (reset and proof of empty tables, auto-webhook, 5,200 fresh tokens,
  Gatling `PlaceOrders`, checks, drain, `count-gap.sh`, Grafana panels). The same JVMs served both runs, so run 2
  ran on JVMs that run 1 had warmed. The R2 re-run instead had fresh JVMs plus the gate.
- Instruments (throwaway, outside the repo):
  - **Connection sampler**, run outside Claude Code's sandbox with your approval. About every 250 ms it runs
    `netstat -ano` (IPv4 and IPv6) and counts rows on :8080 by state, separately for the server side (local port
    8080) and the client side (remote port 8080). It also reads the Gateway's CPU time.
  - **Windows TCP counters** before and after the run (`Get-Counter '\TCPv4\Connection Failures'` and the same for
    TCPv6, resets, passive opens), taken outside the sandbox.
  - **Gateway thread dumps** with `jcmd <pid> Thread.print -l -e`. `-l` names lock owners, and each dump shows each
    thread's CPU time.

### Run 1 (18:29): disturbed, not a result

- **The sampler missed the whole run, and that was my bug.** I read the before-counters with `netstat -s` without
  `-n`. That also lists every connection and looks up a host name for each one. Under load it took about 2
  minutes, so sampling began after Gatling had ended (18:30:38).
- **The dumps took 6.4, 6.1 and 0.9 s each** (the `jcmd` attach was slow on the loaded laptop), and two of them
  fell while requests were still in flight.
- **The numbers moved far from every earlier R2:** 1,870 refused plus 2 x 60 s timeouts, Gatling p99 21.9 s,
  Order server-side p99 8.5 s, Order pool up to 6 active and 41 waiting, 1,742 Redis timeouts.
- Still true for run 1: 500 / 500 sold, `verify` and `check-orders` OK, Version B gap 0, breaker closed.
- Its three dumps are used below only as extra snapshots of the same Gateway JVM (process 33220).
- After this run the sampler was fixed: counters from `Get-Counter` (1.5 s) instead of `netstat -s`, a 2 s dry run
  before arming, and two dumps instead of three, with `-l`.

### Run 2 (Gatling 18:38:42 to 18:39:18, 36 s): results

| | Run 2 (this) | R2 re-run | Part D R2 | R2F |
|---|---|---|---|---|
| JVMs | warmed by gate + run 1 | fresh + gate | warmed by R1 | fresh |
| Orders sold / expected (item 1) | **500 / 500** | 500 / 500 | 500 / 500 | 0 / 500 |
| `place-order` statuses | 201 x 500, 409 x 1,418, **no response x 3,082** | 201 x 500, 409 x 522, no response x 3,978 | 201 x 500, 409 x 1,555, 503 x 46, no response x 2,899 | 201 x 0, 409 x 1,234, 503 x 505, no response x 3,261 |
| Errors | `j.n.ConnectException: Connection refused: getsockopt` x 3,082 (100 %); no timeouts | same text x 3,978 | same | same |
| Warm-up statuses | 201 x 50, 409 x 150 | same | same | 503 x 200 |
| Oversell (`verify.sh`, `check-orders.sh`, both items) | none | none | none | none |
| Gap, Version B (after drain) | 0 (drain 30 s) | 0 | 0 | 0 |
| Gatling p50 / p95 / p99, answered only (ms) | 3,396 / 6,910 / 8,046 | 3,553 / 5,337 / 6,022 | 3,487 / 9,989 / 12,374 | 9,747 / 18,253 / 20,301 |
| Gatling p50 / p95 / p99, all 5,000 (ms) | 1,525 / 5,866 / 7,388 | 943 / 4,173 / 5,349 | 53 / 6,313 / 11,798 | 3,410 / 15,992 / 19,288 |
| Refused requests: min / p99 / max (ms) | 1 / 3,379 / 3,679 | 1 / 3,902 / 5,148 | n/a | n/a |
| Order server-side p99, `POST /api/orders` (Grafana, max of windows) | 3.20 s | 0.90 s | 12.66 s | 7.94 s |
| Inventory server-side p99, reserve (same) | 0.32 s | 0.32 s | 0.11 s | 0.88 s |
| Order pool: max active / pending (Grafana) | 3 / 0 | 3 / 0 | 10 / 191 | 10 / 23 |
| Inventory pool: max active / pending (Grafana) | 1 / 0 | 2 / 0 | 4 / 0 | 10 / 177 |
| Gateway Redis timeouts (EVALSHA, 500 ms, fail open) | **758** (18:39:05 to 18:39:15) | 28 | 918 | 987 |
| Payment breaker | `closed` all run | `closed` | not recorded | opened |
| Reconciler actions / alerts | 0 / 0 (all four reasons) | 0 / 0 | n/a | n/a |

**Against the targets:** Gatling p99 8.0 s answered-only and 7.4 s for all requests, so **p99 < 500 ms not met**.
5,000 were offered over 10 s and 3,082 refused, so **5,000 concurrent not reached**. Zero oversell held (one run).
This run carried a sampler and a mid-ramp thread dump (5.7 s to take), so it is not a clean repeat of the R2 re-run.
The two runs differ in refusals (3,082 against 3,978) and in Redis timeouts (758 against 28). Not explained.

### Did the queue fill?

**What the counters show (taken 18:38:42.6 and 18:40:24.4):**

| Windows counter | Before | After | Change |
|---|---|---|---|
| TCPv6 Connection Failures | 33,673 | 36,755 | **+3,082** |
| TCPv4 Connection Failures | 70,731 | 74,243 | +3,512 |
| TCPv4 / TCPv6 Connections Reset | 21,925 / 9,558 | 21,929 / 9,579 | +4 / +21 |
| TCPv4 / TCPv6 Connections Passive | 73,923 / 66,688 | 76,758 / 67,138 | +2,835 / +450 |

- **The IPv6 failures rose by exactly the refused count (3,082).** IPv4 rose by 3,512 over the same 100 s, which
  also covers every other service's traffic. One reading fits both: each refused buyer was refused once on `::1`
  and once on `127.0.0.1` (Gatling tries both addresses of `localhost`; run 1's timeouts name both). That reading
  is **not proven**.
- Resets hardly moved. So the refusals are failed connection attempts on a port that was listening, because
  2,118 buyers in the same seconds did get through.
- On this machine, the earlier slow-accept probe produced exactly this signature: same error, failures rising one
  for one with refusals. That probe's cause was a full accept queue.

**What the sampler showed:**

| Sample (clock) | netstat took | Server-side ESTABLISHED / SYN_RECEIVED / CLOSE_WAIT on :8080 | Client-side TIME_WAIT |
|---|---|---|---|
| 18:39:04.3 (before the burst) | 0.6 s | 1 / 0 / 0 | 200 |
| 18:39:04.8 | **6.9 s** | **485** / 0 / 10 | 562 |
| 18:39:11.8 | **4.3 s** | **788** / 0 / 45 | 993 |
| 18:39:16.1 | 1.7 s | 214 / 0 / 30 | 1,847 |
| 18:39:17.8 (after) | 0.5 s | 1 / 0 / 0 | 2,089 |

**What the sampler could not see:**
- **Too few samples.** During the measured ramp a single `netstat` took 4 to 7 s, so the 13 s of the ramp have
  only 3 samples, each smeared over seconds instead of a snapshot at one moment.
- **Queued and accepted look the same.** `netstat` shows a connection waiting in the accept queue and a connection
  the Gateway has already accepted both as ESTABLISHED. So 485 and 788 are queued plus in-flight connections, and
  the queued share (the "about 200") **cannot be read off**.
- **No SYN_RECEIVED pile-up.** It stayed 0, as in the probe, so on this Windows that state is not the sign.
- No accepted-connection count from the Gateway (Reactor Netty metrics are not exported).

**Verdict:** refusals on a live listener, with failures rising exactly with refusals and no resets, match the
full-accept-queue signature reproduced earlier on this laptop. **The queue itself (about 200 waiting, not yet
accepted) was not directly seen.** Other causes of a refused connection on a listening Windows port are not ruled
out.

### What the Gateway's event-loop threads were doing

The Gateway has 8 `reactor-http-nio` threads and no separate accept thread (the library default, read earlier).
Five dumps exist, all from the same Gateway JVM.

| Dump (time the JVM printed it) | Busy event loops: what the frames show | Idle in `select` |
|---|---|---|
| Run 2, 18:39:04, at the ramp's start (took 0.45 s) | none | 8 |
| Run 2, 18:39:14, mid-ramp (took 5.7 s) | `nio-1`: in `sun.nio.ch.Net.accept`, owns the server socket's accept lock, **accepting**. `nio-2`: **owns** the `ReentrantLock` of `DefaultRedisScript.getSha1` and is inside `ResourceScriptSource.retrieveLastModifiedTime` → `AbstractFileResolvingResource.lastModified` → `customizeConnection`. `nio-5`, `nio-6`: **parked on that same lock** (`getSha1`, line 86) | 4 |
| Run 1, 18:29:43, end of the ramp (took 6.4 s) | `nio-2`, `nio-3`, `nio-5`, `nio-6`: **parked on the `getSha1` lock**. `nio-7`: inside `getSha1` (line 89), `ResourceScriptSource.isModified` → `ResourceUtils.getFile` building a `FileNotFoundException`. `nio-1`: `AuthFilter.filter` → `JwtVerifier.verify` → jjwt HMAC-SHA256 (`Mac.init`). `nio-4`: reading a downstream response (`HttpClientOperations`) | 1 |
| Run 1, 18:29:49 | 5 in socket read/write (`SocketDispatcher`), `nio-6` parked on the `getSha1` lock | 2 |
| Run 1, 18:29:54 | none | 8 |

**What the code does,** read from the jars:
- **The lock (spring-data-redis 4.1.1).** `DefaultRedisScript.getSha1()` takes one shared lock (line 86) on
  **every** call. Inside it, it asks `scriptSource.isModified()` (line 89), then unlocks.
- **The script (Spring Cloud Gateway 5.0.3).** It builds the rate limiter's script as
  `new ResourceScriptSource(new ClassPathResource("META-INF/scripts/request_rate_limiter.lua"))`. That file sits
  inside a nested jar.
- **The cost.** Every rate-limited `POST /api/orders` therefore checks the script file's last-modified time while
  holding the shared lock. In the dumps that check goes through `ResourceUtils.getFile`, which throws
  `FileNotFoundException`, and through a URL connection into the jar.

**What the dumps show:**
- **The acceptor is `nio-1`,** the only thread ever seen in `Net.accept`.
- **`nio-1` also handles requests.** In run 1's dump it was verifying a JWT. That is the shared-loop design.
- **Event loops lose time to the rate limiter's script lock.** Up to 5 of 8 at once were held there: 4 parked and
  1 inside.

**What they do not show:**
- The acceptor (`nio-1`) parked on that lock. In the five snapshots it never was.
- That accepts were delayed at the moments buyers were refused. Five snapshots cannot show timing.
- Whether the 758 Redis timeouts come from the same stall. Lettuce times the command after it is sent; not traced.

**Gateway CPU:** 11.0 s of CPU from 18:39:04.3 to 18:39:17.8 (13.5 s), about 0.8 of one core out of 8. The
laptop as a whole was saturated (one `netstat` took 7 s). The Gateway was not.

### Still unproven

- That the accept queue held about 200 waiting connections at the moments of refusal.
- That the acceptor thread was the slow part, rather than the operating system on a saturated laptop. The Gateway
  used under one core while the machine was saturated.
- Why run 2 had 758 Redis timeouts and the R2 re-run 28.

**Likely fix (not applied):** a separate accept thread (`reactor.netty.ioSelectCount=1`) plus a rate-limiter script
that does no per-call lock and file check. It would stop request work from delaying accepts, so buyers now refused
would wait or be answered instead (slower). Unmeasured.
