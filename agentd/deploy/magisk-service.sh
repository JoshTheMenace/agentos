#!/system/bin/sh
# AgentOS boot + watchdog (Magisk, root). Install: /data/adb/service.d/agentos-agentd.sh, mode 755.
# Runs agentd as the Termux user (uid 10401) but OUTSIDE the Termux app. Why: lmkd kills the Termux app under
# memory pressure (exit-info LOW_MEMORY, 4x on 10-27, e.g. with llama-server loaded) and Android kills every child
# of the app with it. A process started here is not the app's child, so lmkd and the phantom-process killer never
# see it; if node itself exits, termux-boot.sh's loop restarts it in 5 s, and this loop restarts that loop in 3 s.
# Undo: delete the installed file, `echo agentd > /sys/power/wake_unlock`, cp termux-boot.sh to ~/.termux/boot/agentos-agentd.
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 2; done
settings put global settings_enable_monitor_phantom_procs false   # Android 12L+: don't kill Termux's extra processes
device_config set_sync_disabled_for_tests persistent
device_config put activity_manager max_phantom_processes 2147483647
dumpsys deviceidle whitelist +com.termux >/dev/null
cmd wifi force-low-latency-mode enabled   # no Wi-Fi power save: it held Codex replies 2-6 s (keyboard/LATENCY.md)
echo agentd > /sys/power/wake_lock      # keep the CPU awake (Termux's own wake lock lived in the app)
echo -900 > /proc/$$/oom_score_adj       # inherited by agentd; lmkd doesn't track it anyway
T=/data/data/com.termux/files
while true; do
  pgrep -f '^node server.js$' >/dev/null || AGENTOS_NATIVE=1 PREFIX=$T/usr HOME=$T/home TMPDIR=$T/usr/tmp PATH=$T/usr/bin:/system/bin LANG=en_US.UTF-8 \
    $T/usr/bin/setpriv --reuid=10401 --regid=10401 --groups=3003,9997,20401,50401 --inh-caps=-all \
    $T/usr/bin/sh $T/home/agentos/agentd/deploy/termux-boot.sh </dev/null >/dev/null 2>&1 &
  sleep 3
done
