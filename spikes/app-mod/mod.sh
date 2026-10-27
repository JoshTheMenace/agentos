#!/bin/sh
# ./mod.sh install | enable | disable | apply specs/tip.json | remove tip | list
# Set LOCKED=1 when the caller already holds /tmp/agentos-device.lock.
# enable/disable add/remove only our accessibility service and keep everyone else's (same rule as android/install.sh).
set -eu
cd "$(dirname "$0")"
S=${ANDROID_SERIAL:-ZY22J3TK8S}
SVC=local.agentos.appmod/local.agentos.appmod.ModService
a() { adb -s "$S" "$@"; }
lock() { [ "${LOCKED:-}" = 1 ] && return; until mkdir /tmp/agentos-device.lock 2>/dev/null; do echo "device busy, retrying..." >&2; sleep 3; done; trap 'rmdir /tmp/agentos-device.lock' EXIT; }
case "$1" in
  install) lock; a install -r build/appmod.apk
    a shell "su -c 'appops set local.agentos.appmod ACCESS_RESTRICTED_SETTINGS allow'" ;;
  enable) lock; cur=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
    case ":$cur:" in *":$SVC:"*) ;; *) { [ "$cur" = null ] || [ -z "$cur" ]; } && new=$SVC || new="$cur:$SVC"
      a shell settings put secure enabled_accessibility_services "$new" ;; esac
    a shell settings get secure enabled_accessibility_services ;;
  disable) lock; cur=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
    new=$(echo ":$cur:" | sed "s|:$SVC:|:|; s|^:||; s|:$||")
    a shell settings put secure enabled_accessibility_services "$new"
    a shell settings get secure enabled_accessibility_services ;;
  apply) a shell am broadcast -a local.agentos.appmod.APPLY -n local.agentos.appmod/.ModCmd --es spec64 "$(base64 < "$2" | tr -d '\n')" | sed -n 's/.*data="\(.*\)"/\1/p' ;;
  remove) a shell am broadcast -a local.agentos.appmod.REMOVE -n local.agentos.appmod/.ModCmd --es id "$2" | sed -n 's/.*data="\(.*\)"/\1/p' ;;
  list) a shell am broadcast -a local.agentos.appmod.LIST -n local.agentos.appmod/.ModCmd | sed -n 's/.*data="\(.*\)"/\1/p' ;;
esac
