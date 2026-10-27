#!/bin/sh
# Installs build/agentos.apk on the phone and switches the AgentOS accessibility service on (keeps other services).
# Holds the shared device lock while it runs.
set -eu
cd "$(dirname "$0")"
S=${ANDROID_SERIAL:-ZY22J3TK8S}
SVC=local.agentos/local.agentos.AgentA11y
until mkdir /tmp/agentos-device.lock 2>/dev/null; do echo "device busy (lock held), retrying..."; sleep 5; done
trap 'rmdir /tmp/agentos-device.lock' EXIT
adb -s "$S" install -r build/agentos.apk
uid=$(adb -s "$S" shell pm list packages -U local.agentos | grep "^package:local.agentos " | sed "s/.*uid://" | tr -d '\r')
# Root for the referee jobs only (FLAG_SECURE check, iptables): Magisk policy 2 = allow, no toast.
adb -s "$S" shell "su -c 'magisk --sqlite \"REPLACE INTO policies (uid,policy,until,logging,notification) VALUES($uid,2,0,1,0)\"'"
adb -s "$S" shell "su -c 'appops set local.agentos ACCESS_RESTRICTED_SETTINGS allow; dumpsys deviceidle whitelist +local.agentos >/dev/null'"
# The old prototype also listens on :7777; stop its service so ours can bind.
adb -s "$S" shell "su -c 'am stopservice local.agentos.uilab/.Tools >/dev/null 2>&1 || true'"
# Append our service if missing; never drop anyone else's. Android rebinds it after an update.
cur=$(adb -s "$S" shell settings get secure enabled_accessibility_services | tr -d '\r')
case ":$cur:" in *":$SVC:"*) ;; *) [ "$cur" = null ] || [ -z "$cur" ] && new=$SVC || new="$cur:$SVC"
  adb -s "$S" shell settings put secure enabled_accessibility_services "$new" ;; esac
adb -s "$S" shell settings put secure accessibility_enabled 1
# Notification feed (Notifs): read notifications + reply through their Reply action; post the test chat.
adb -s "$S" shell "cmd notification allow_listener local.agentos/local.agentos.Notifs; pm grant local.agentos android.permission.POST_NOTIFICATIONS"
adb -s "$S" shell "settings get secure enabled_accessibility_services"
echo "installed; uid $uid"

