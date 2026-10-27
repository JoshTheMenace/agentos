#!/system/bin/sh
# ON-PHONE wrapper (run as root): reuse the existing signed-in lab install.
# Usage: su -c 'sh /data/local/tmp/agentos-codex-env.sh <codex args...>'
L=/data/local/tmp/android-agent-lab; H=$L/codex-home
exec env HOME=$H CODEX_HOME=$H TMPDIR=$H/tmp SHELL=/system/bin/sh PATH=/system/bin:/system/xbin:/vendor/bin \
  HTTPS_PROXY=http://127.0.0.1:18080 HTTP_PROXY=http://127.0.0.1:18080 \
  SSL_CERT_FILE=$H/ca-bundle.pem SSL_CERT_DIR=/system/etc/security/cacerts \
  $L/codex-0.163.0/codex-aarch64-unknown-linux-musl "$@"
