#!/usr/bin/env python3
# scripts/withphone python3 spikes/voice/bench-up.py [--init] [--baseline <git ref>]
# --init freezes dependencies once. Later runs replace only voice.js and the benchmark server.
import argparse, gzip, os, pathlib, shlex, subprocess as sp, tempfile
repo = pathlib.Path(__file__).resolve().parents[2]
os.chdir(repo)
assert pathlib.Path('/tmp/agentos-device.lock/pid').read_text().strip() == str(os.getppid()), 'Run through scripts/withphone'
p = argparse.ArgumentParser()
p.add_argument('--init', action='store_true')
p.add_argument('--baseline')
a = p.parse_args()
voice = sp.check_output(['git', 'show', a.baseline + ':agentd/voice.js']) if a.baseline else pathlib.Path('agentd/voice.js').read_bytes()
sp.run(['node', '--check', '--input-type=module'], input=voice, check=True)
sp.run(['node', '--check', 'spikes/voice/bench.mjs'], check=True)
phone = '/data/local/tmp/voice-latency'
with tempfile.TemporaryDirectory() as tmp:
    src = pathlib.Path(tmp, 'voice.js'); src.write_bytes(voice)
    for local, remote in [(src, phone + '.js'), ('spikes/voice/bench.mjs', phone + '.mjs')]:
        sp.run(['adb', 'push', str(local), remote], check=True, stdout=sp.DEVNULL)
    if a.init:
        archive = str(pathlib.Path(tmp, 'bench.tgz'))
        pathlib.Path(archive).write_bytes(gzip.compress(sp.check_output(['git', 'archive', '--format=tar', 'HEAD', 'agentd', 'spikes/voice'])))
        sp.run(['adb', 'push', archive, phone + '.tgz'], check=True, stdout=sp.DEVNULL)
    token = sp.check_output(['adb', 'shell', 'su', '-c', 'cat /data/data/local.agentos/files/token'], text=True).strip()
    script = f'''set -e
D=~/agentos/voice-latency; mkdir -p "$D"; cd "$D"
[ ! -f pid ] || kill $(cat pid) 2>/dev/null || true
{f'tar xzf {phone}.tgz; ln -sfn ~/agentos/agentd/node_modules agentd/node_modules' if a.init else ''}
rm -f agentd/voice.js spikes/voice/bench.mjs
cp {phone}.js agentd/voice.js; cp {phone}.mjs spikes/voice/bench.mjs
export PHONE_TOKEN={shlex.quote(token)} AGENTOS_HOME=$HOME/.agentos-voice-latency AGENTD_CLIENT_PKGS= VOICE_VISION=0 CODEX_BIN=$HOME/agentos/agentd/deploy/codex-root.sh
setsid nohup node spikes/voice/bench.mjs > bench.log 2>&1 < /dev/null & echo $! > pid
'''
    sp.run(['agentd/deploy/tx.sh', script], check=True)
    sp.run(['adb', 'forward', 'tcp:18793', 'tcp:8793'], check=True, stdout=sp.DEVNULL)
