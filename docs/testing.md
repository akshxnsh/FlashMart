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

