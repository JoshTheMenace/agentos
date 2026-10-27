#!/usr/bin/env python3
"""Drive `codex app-server` (stdio JSON-RPC) on the phone through adb.
Usage: appserver_probe.py [--turn "say hi"] [--timeout 120]
Needs /data/local/tmp/agentos-codex-env.sh on the phone (see codex-env.sh)."""
import argparse, json, subprocess, sys, time

ap = argparse.ArgumentParser()
ap.add_argument("--serial", default="ZY22J3TK8S")
ap.add_argument("--turn", help="prompt for one real turn (needs network + login)")
ap.add_argument("--timeout", type=float, default=120)
ap.add_argument("--sandbox", default="read-only", help="read-only | workspace-write | danger-full-access (bwrap fails on Android)")
a = ap.parse_args()
adb = ["adb", "-s", a.serial, "shell"]
cmd = "su -c 'sh /data/local/tmp/agentos-codex-env.sh app-server'"
p = subprocess.Popen(adb + ["-T", cmd], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)
t0, nid = time.time(), 0

def rss():  # VmRSS (kB) of the app-server process, read as root
    out = subprocess.run(adb + ["su -c 'for p in $(pgrep -f \"musl app-server\"); do grep VmRSS /proc/$p/status; done'"],
                         capture_output=True, text=True).stdout.split()
    return out[1] + " kB" if len(out) > 1 else "?"

def send(method, params=None, notify=False):
    global nid
    msg = {"method": method, **({"params": params} if params is not None else {})}
    if not notify:
        nid += 1; msg["id"] = nid
    p.stdin.write(json.dumps(msg) + "\n"); p.stdin.flush()
    return nid

def until(pred, limit):  # read lines until pred(msg) is truthy; auto-decline server requests
    end = time.time() + limit
    while time.time() < end:
        line = p.stdout.readline()
        if not line: sys.exit("app-server exited")
        try: m = json.loads(line)
        except ValueError: continue
        if "id" in m and "method" in m:  # server->client request (approval etc.)
            p.stdin.write(json.dumps({"id": m["id"], "result": {"decision": "decline"}}) + "\n"); p.stdin.flush()
        if pred(m): return m
    sys.exit(f"timeout after {limit}s")

i = send("initialize", {"clientInfo": {"name": "agentos_probe", "title": "AgentOS probe", "version": "0.1"}})
r = until(lambda m: m.get("id") == i, 60)
print(f"initialize {time.time()-t0:.2f}s ->", json.dumps(r.get("result") or r.get("error"))[:300])
send("initialized", notify=True)
time.sleep(3); print("idle RSS:", rss())
acct = send("account/read", {"refreshToken": False})
r = until(lambda m: m.get("id") == acct, 30)
res = r.get("result") or r.get("error") or {}
ac = res.get("account") or {}; print("account/read:", ac.get("type"), ac.get("planType"), "| requiresOpenaiAuth:", res.get("requiresOpenaiAuth"))

if a.turn:
    s = send("thread/start", {"ephemeral": True, "sandbox": a.sandbox, "approvalPolicy": "never",
                              "cwd": "/data/local/tmp/android-agent-lab/codex-home/tmp"})
    r = until(lambda m: m.get("id") == s, 60)
    tid = (r.get("result") or {}).get("thread", {}).get("id") or sys.exit(f"thread/start failed: {r}")
    t1 = time.time(); first = None; text = []
    send("turn/start", {"threadId": tid, "input": [{"type": "text", "text": a.turn}]})
    def watch(m):
        global first
        meth = m.get("method", "")
        if meth == "item/agentMessage/delta":
            first = first or time.time() - t1; text.append(m["params"].get("delta", ""))
        elif meth in ("error", "turn/completed"):
            print(meth, json.dumps(m.get("params"))[:400])
        elif meth == "item/completed" and m["params"]["item"].get("type") == "commandExecution":
            it = m["params"]["item"]; print("exec:", it.get("command"), "->", it.get("exitCode"), repr((it.get("aggregatedOutput") or "")[:200]))
        return meth == "turn/completed"
    until(watch, a.timeout)
    print(f"turn: first token {first and round(first,2)}s, total {time.time()-t1:.2f}s, reply={''.join(text)!r}")
    print("post-turn RSS:", rss())
p.stdin.close(); p.terminate()
