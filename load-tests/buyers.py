"""Test buyers for the PlaceOrders load test. Called by create-users.sh and
make-tokens.sh; not meant to be run by hand.

Both steps talk to Identity DIRECTLY (default http://localhost:8081), never
through the Gateway: the Gateway's /api/auth/** limiter is 1 request/s per IP,
so 5,200 signups through it would take well over an hour.

TEST-ONLY. The emails below and PASSWORD are fixed and public on purpose, so
the steps are repeatable. They are for a local load-test database only; never
create these users anywhere real.

Two groups, with different emails so a warm-up buyer is never a measured one:
  measured: lt-buyer-00001@loadtest.flashmart.test ... (BUYERS of them)
  warm-up:  lt-warmup-00001@loadtest.flashmart.test ... (WARMUP of them)

Standard library only (Python 3), so nothing to install.
"""
import concurrent.futures
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request

# TEST-ONLY password, shared by every load-test buyer. Not a secret.
PASSWORD = "loadtest-only-password-not-a-secret"
DOMAIN = "loadtest.flashmart.test"


def emails(prefix, count):
    return [f"lt-{prefix}-{i:05d}@{DOMAIN}" for i in range(1, count + 1)]


def post(url, body, timeout):
    """POST JSON, return (status, parsed body or None). Never raises on an HTTP status."""
    req = urllib.request.Request(url, json.dumps(body).encode(), {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, json.load(res)
    except urllib.error.HTTPError as e:
        return e.code, None
    except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
        return f"io-error:{type(e).__name__}", None


def run_all(task, items, concurrency):
    """Runs task(item) on a thread pool, returns the results in input order."""
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        return list(pool.map(task, items))


def count(results):
    out = {}
    for r in results:
        out[str(r)] = out.get(str(r), 0) + 1
    return dict(sorted(out.items()))


def create(base, buyers, warmup, concurrency, timeout):
    everyone = emails("buyer", buyers) + emails("warmup", warmup)

    def signup(email):
        status, _ = post(f"{base}/api/auth/signup",
                         {"name": "Load Test Buyer", "email": email, "password": PASSWORD}, timeout)
        return status

    start = time.monotonic()
    statuses = run_all(signup, everyone, concurrency)
    took = time.monotonic() - start
    counts = count(statuses)
    created, existing = counts.get("201", 0), counts.get("409", 0)
    print(f"create-users: {len(everyone)} emails ({buyers} measured + {warmup} warm-up), "
          f"concurrency {concurrency}, took {took:.1f} s")
    print(f"create-users: created (201) {created}, already existed (409, skipped) {existing}, "
          f"all statuses {counts}")
    # Anything other than 201 / 409 means some buyer may not exist: fail loudly.
    return 0 if created + existing == len(everyone) else 1


def tokens(base, buyers, warmup, concurrency, timeout, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    lock = threading.Lock()
    ttl = []

    def login(email):
        status, body = post(f"{base}/api/auth/login", {"email": email, "password": PASSWORD}, timeout)
        if status == 200 and body and body.get("accessToken"):
            with lock:
                ttl.append(int(body["expiresIn"]))
            return body["accessToken"]
        return f"FAILED:{status}"

    # The clock starts BEFORE the first request: the earliest token can only expire
    # later than started + ttl, so this budget is on the safe side.
    started_wall = time.time()
    start = time.monotonic()
    results = {}
    for group, n in (("warmup", warmup), ("buyers", buyers)):
        results[group] = run_all(login, emails("buyer" if group == "buyers" else "warmup", n), concurrency)
    took = time.monotonic() - start

    failed = [t for r in results.values() for t in r if t.startswith("FAILED:")]
    print(f"make-tokens: {buyers + warmup} logins ({buyers} measured + {warmup} warm-up), "
          f"concurrency {concurrency}, took {took:.1f} s")
    if failed:
        print(f"make-tokens: {len(failed)} logins FAILED, statuses {count(failed)}; no files written")
        return 1

    for group, toks in results.items():
        with open(os.path.join(out_dir, f"{group}.csv"), "w", newline="\n") as f:
            f.write("token\n")
            f.writelines(t + "\n" for t in toks)
    expires_at = int(started_wall) + min(ttl)
    with open(os.path.join(out_dir, "expires-at.txt"), "w") as f:
        f.write(f"{expires_at}\n")
    left = expires_at - time.time()
    print(f"make-tokens: access-token lifetime {min(ttl)} s (expiresIn); earliest token expires at "
          f"{time.strftime('%H:%M:%S', time.localtime(expires_at))}; budget left now {left:.0f} s "
          f"({left / 60:.1f} min). The whole Gatling run (warm-up + measured) must finish inside it.")
    print(f"make-tokens: wrote {out_dir}/warmup.csv, buyers.csv, expires-at.txt")
    return 0


if __name__ == "__main__":
    step = sys.argv[1]
    base = os.environ.get("IDENTITY_URL", "http://localhost:8081")
    buyers = int(os.environ.get("BUYERS", "5000"))
    warmup = int(os.environ.get("WARMUP", "200"))
    concurrency = int(os.environ.get("CONCURRENCY", str(os.cpu_count() or 4)))
    timeout = float(os.environ.get("HTTP_TIMEOUT_S", "30"))
    if step == "create":
        sys.exit(create(base, buyers, warmup, concurrency, timeout))
    if step == "tokens":
        sys.exit(tokens(base, buyers, warmup, concurrency, timeout, sys.argv[2]))
    sys.exit(f"unknown step {step}")
