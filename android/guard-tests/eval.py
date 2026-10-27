#!/usr/bin/env python3
"""Precision/recall of the guards on the saved test set (screens/*.json, labels.json).
  before = the Prompt Guard label saved in each dump (the APK installed at capture time)
  after  = the installed APK re-scoring the same nodes (guard.screen; notifications: guard.classify, as Notifs does)
  regex  = agentd/guard.js on every node (agentd withholds those and the reviewer counts them as a flag)
  python3 android/guard-tests/eval.py [--before-only] [--regex path/to/guard.js]"""
import importlib.util, json, os, subprocess, sys
HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("tc", os.path.join(HERE, "..", "tools-client.py"))
tc = importlib.util.module_from_spec(spec); spec.loader.exec_module(tc)
REGEX = sys.argv[sys.argv.index("--regex") + 1] if "--regex" in sys.argv else os.path.join(HERE, "..", "..", "agentd", "guard.js")
JS = """import fs from 'fs'; const { classify } = await import(process.argv[1]);
const s = JSON.parse(fs.readFileSync(0)); const t = s.notification ? [s.notification.text] :
  (s.nodes || []).map((n) => [n.text, n.desc, n.hint].filter(Boolean).join(' ')).filter((x) => x.length >= 12);
console.log(t.some((x) => classify(x).label !== 'benign'));"""


def regex(s):
    r = subprocess.run(["node", "--input-type=module", "-e", JS, os.path.abspath(REGEX)], input=json.dumps(s), capture_output=True, text=True)
    return r.stdout.strip() == "true"


def stats(rows, k):
    tp = sum(r[k] and r["want"] for r in rows); fp = sum(r[k] and not r["want"] for r in rows); fn = sum(r["want"] and not r[k] for r in rows)
    return f"P {tp}/{tp + fp} = {tp / max(1, tp + fp):.2f}  R {tp}/{tp + fn} = {tp / max(1, tp + fn):.2f}  FP {fp}"


def main():
    labels = {k: v for k, v in json.load(open(os.path.join(HERE, "labels.json"))).items() if not k.startswith("_")}
    tok = None if "--before-only" in sys.argv else tc.token()
    rows = []
    for c, want in labels.items():
        s = json.load(open(os.path.join(HERE, "screens", c + ".json")))
        n = s.get("notification")
        before = (n["guard"] if n else s["guard"])["label"] == "MALICIOUS"
        after = before
        if tok:
            g = tc.call(tok, "guard.classify", {"text": f"{n['app']}: {n['sender']}: {n['text']}"}) if n else tc.call(tok, "guard.screen", {"nodes": s["nodes"]})
            after = g["label"] == "MALICIOUS"
        rows.append(dict(case=c, want=want, before=before, after=after, regex=regex(s)))
        r = rows[-1]; r["sys_before"], r["sys_after"] = r["before"] or r["regex"], r["after"] or r["regex"]
        mark = lambda v: ("FLAG" if v else "-") + ("" if v == want else " ✗")
        print(f"{c:26} want={'FLAG' if want else '-':5} PG2 before={mark(before):7} after={mark(after):7} regex={mark(r['regex'])}")
    print(f"\nPrompt Guard  before: {stats(rows, 'before')}\nPrompt Guard  after:  {stats(rows, 'after')}")
    print(f"regex (agentd):       {stats(rows, 'regex')}\nPG2 or regex  before: {stats(rows, 'sys_before')}\nPG2 or regex  after:  {stats(rows, 'sys_after')}")


if __name__ == "__main__":
    main()
