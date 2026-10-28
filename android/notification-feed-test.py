#!/usr/bin/env python3
"""Run through scripts/withphone. Only Pigeon receives test actions; real apps are read-only.

Uses the existing agentd reviewer, never taps/confirms an approval or enables auto-reply.
"""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("client", ROOT / "android/tools-client.py")
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)
PKG, NAME = "local.agentos.pigeon", "AgentOS test notification feed " + str(int(time.time()))
token = client.adb("exec-out", "su -c 'cat /data/data/local.agentos/files/token'")
timings = {}
ASK = """import WebSocket from 'ws'; import {agentdToken,cfg} from './config.js';
const ws=new WebSocket(`ws://${cfg.host}:${cfg.port}`,{headers:{authorization:`Bearer ${agentdToken()}`}});
const timer=setTimeout(()=>{ws.send(JSON.stringify({type:'stop'}));ws.close();},120000);
ws.on('open',()=>ws.send(JSON.stringify({type:'ask',text:process.argv[1]})));
ws.on('message',raw=>{const m=JSON.parse(raw);if(m.type==='turn.done'||m.type==='error'&&['busy','provider_unavailable','bad_json'].includes(m.code))ws.close();});
ws.on('close',()=>clearTimeout(timer));ws.on('error',()=>{clearTimeout(timer);process.exitCode=1;});"""


def rpc(method, params=None):
    start = time.monotonic()
    result = client.call(token, method, params)
    timings.setdefault(method, []).append(round((time.monotonic() - start) * 1000))
    return result


def broadcast(action, **extras):
    args = ["am", "broadcast", "--include-stopped-packages", "-n", PKG + "/.TestNotifications", "-a", PKG + ".TEST_" + action,
            "--es", "conversation", NAME]
    for key, value in extras.items():
        args += ["--es", key, value]
    client.adb("shell", shlex.join(args))


def conversation():
    result = rpc("notifications.conversations", {"pkg": PKG})
    assert result["ok"] and result["listener"], result
    return next((x for x in result["items"] if x["conversation"] == NAME), None)


def wait_for(check, seconds=10):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        result = check()
        if result:
            return result
        time.sleep(.05 if seconds <= 10 else .5)
    raise AssertionError("notification state did not arrive")


def messages():
    return rpc("notifications.conversation", {"conversation_id": conversation()["conversation_id"]})["items"]


def notification():
    return next((n for n in rpc("notifications.list", {"n": 40})["notifications"] if n["pkg"] == PKG and n["conversation"] == NAME), {})


def deny_own_pending():
    for a in rpc("approval.list").get("approvals", []):
        if a.get("pkg") == PKG and NAME in a.get("target", ""):
            rpc("approval.deny", {"approval_id": a["approval_id"], "utterance": "Pigeon test cleanup"})


def ask(text, check):
    # A real agent turn supplies the existing reviewer with the exact, Pigeon-only test request.
    command = "cd ~/agentos/agentd && node --input-type=module -e " + shlex.quote(ASK) + ' ' + shlex.quote('First call task_start with apps ["local.agentos.pigeon"] for this Pigeon test, so the existing reviewer has my exact request. ' + text)
    output = open(os.devnull, "w")
    proc = subprocess.Popen([str(ROOT / "agentd/deploy/tx.sh"), command], stdout=output, stderr=output)
    start = time.monotonic()
    try:
        wait_for(lambda: check() or proc.poll() is not None, 60)
        if not check():
            return {"completed": False, "client_exited": True}
        proc.wait(timeout=30)
        return {"completed": True, "ms": round((time.monotonic() - start) * 1000)}
    except (AssertionError, subprocess.TimeoutExpired):
        deny_own_pending()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.terminate()
        return {"completed": bool(check()), "approval_left_to_owner": True}
    finally:
        output.close()


try:
    idle = rpc("approval.list")
    assert not idle.get("approvals") and not idle.get("task"), "phone has another task or approval; retry after it finishes"
    baseline = rpc("notifications.conversations")
    assert baseline["listener"], "enable the AgentOS notification listener first"
    counts = {}
    for pkg in ["com.linkedin.android", "com.Slack", "com.instagram.android", "com.discord", "com.google.android.gm", "com.google.android.apps.messaging"]:
        counts[pkg] = len(rpc("notifications.conversations", {"pkg": pkg})["items"])
    broadcast("CLEAR")
    since = rpc("notifications.conversations")["cursor"]
    posted = []
    for j in range(4):
        start = time.monotonic()
        broadcast("POST", sender="Pigeon fixture " + str(j % 2), text="Pigeon test code 839271" if j == 0 else "Pigeon incoming test message " + str(j))
        wait_for(lambda: (conversation() or {}).get("message_count", 0) >= j + 1)
        posted.append(round((time.monotonic() - start) * 1000))
    before = conversation()
    assert before["can_reply"] and before["can_mark_read"]
    history = messages()
    assert len(history) == 4 and history[0]["text"].endswith("3"), history
    assert len({m["sender"] for m in history}) == 2
    assert all(m["trusted"] is False and m["source_pkg"] == PKG for m in history)
    code = rpc("notifications.reply", {"key": before["key"], "text": "839271"})
    assert not code["ok"] and code["decision"] == "blocked" and "code/canary" in code["reason"], "historic-message P3 protection failed"
    changes = []
    while True:
        page = rpc("notifications.changes", {"since": since, "pkg": PKG, "n": 1})
        changes += page["items"]
        since = page["next_since"]
        if not page["items"]:
            break
    assert len(changes) == 4 and sum(len(x["messages"]) for x in changes) == 4, changes
    reply = ask(f'Pigeon test only: in conversation "{NAME}", send exactly "Pigeon notification reply test complete." using notifications_reply. Use the live notification tools. Do not open any app or operate any other conversation.',
                lambda: (n := notification()).get("text") == "Pigeon notification reply test complete." and n.get("from_me"))
    marked = ask(f'Pigeon test only: mark conversation "{NAME}" read using notifications_mark_read and its current live notification key. Do not open any app or operate any other conversation.',
                 lambda: notification().get("removed", False))
    broadcast("CLEAR")
    removed = wait_for(lambda: (x := conversation()) and not x["active"] and x)
    assert not removed["can_reply"] and not removed["can_mark_read"]
    assert len(messages()) >= 4, "removal lost the message history"
    for method, params in [("notifications.reply", {"text": "Must not send after removal"}), ("notifications.mark_read", {})]:
        result = rpc(method, {"key": removed["key"], **params})
        assert not result["ok"] and result["decision"] == "blocked", result
    audit = rpc("audit.tail", {"n": 80})
    assert audit["chain_ok"], "broker audit chain failed verification"
    for method, result in [("notifications.reply", reply), ("notifications.mark_read", marked)]:
        assert any(e.get("tool") == method and e.get("pkg") == PKG and e.get("conversation_id") == removed["conversation_id"]
                   and (e.get("ok") or not result["completed"]) for e in audit["entries"]), "missing notification action audit entry"
    summary = {method: {"count": len(ms), "median": statistics.median(ms), "min": min(ms), "max": max(ms)} for method, ms in timings.items()}
    print(json.dumps({"read_only_real_app_counts": counts, "post_to_observed_ms": posted, "rpc_ms": summary,
                      "messaging_style_history": "pass", "dedup_and_pagination": "pass", "historic_code_leak": "blocked", "removed_actions": "blocked",
                      "reply": reply, "mark_read": marked, "audit_chain_ok": audit.get("chain_ok")}, indent=2))
finally:
    deny_own_pending()
    broadcast("CLEAR")
