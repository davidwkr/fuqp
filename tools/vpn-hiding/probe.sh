#!/usr/bin/env bash
# Pull the connectivity code off a device and list the methods VPN hiding (mode B) would hook.
# Usage: probe.sh <adb-serial> [out-dir]
# Read-only on the device. Needs root (su) and build-tools' dexdump. See docs/VPN_HIDING.md.
set -euo pipefail
SERIAL="$1"
OUT="${2:-$(mktemp -d /tmp/fuqp-vpnprobe.XXXX)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
DEXDUMP="$(printf '%s\n' "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/*/dexdump | sort -V | tail -1)"
mkdir -p "$OUT/jars"

sh_dev() { timeout 60 adb -s "$SERIAL" shell "$1" </dev/null | tr -d '\r'; }

{
  echo "sdk=$(sh_dev 'getprop ro.build.version.sdk') release=$(sh_dev 'getprop ro.build.version.release')"
  echo "tethering apex: $(sh_dev 'su -c "cat /apex/com.android.tethering/apex_manifest.pb 2>/dev/null | strings | head -3 | tr \"\n\" \" \""')"
  echo "SYSTEMSERVERCLASSPATH:"
  sh_dev 'su -c "cat /proc/\$(pidof system_server)/environ | tr \"\\0\" \"\\n\" | grep ^SYSTEMSERVERCLASSPATH= | cut -d= -f2 | tr : \"\\n\""'
  echo "STANDALONE_SYSTEMSERVER_JARS:"
  sh_dev 'su -c "cat /proc/\$(pidof system_server)/environ | tr \"\\0\" \"\\n\" | grep ^STANDALONE_SYSTEMSERVER_JARS= | cut -d= -f2 | tr : \"\\n\""'
} > "$OUT/env.txt"

for jar in $(sh_dev 'su -c "ls /apex/com.android.tethering/javalib/*.jar"'); do
  name="$(basename "$jar")"
  sh_dev "su -c 'cp $jar /data/local/tmp/fuqp-probe-$name && chmod 644 /data/local/tmp/fuqp-probe-$name'" >/dev/null
  adb -s "$SERIAL" pull "/data/local/tmp/fuqp-probe-$name" "$OUT/jars/$name" </dev/null >/dev/null
  sh_dev "rm -f /data/local/tmp/fuqp-probe-$name" >/dev/null
done

for jar in "$OUT"/jars/*.jar; do
  d="$OUT/dex/$(basename "$jar" .jar)"; mkdir -p "$d"
  unzip -o -q "$jar" 'classes*.dex' -d "$d" 2>/dev/null || true
  for dex in "$d"/classes*.dex; do
    [ -f "$dex" ] && "$DEXDUMP" "$dex" >> "$OUT/dexdump.txt" 2>/dev/null || true
  done
done

python3 -I "$HERE/methods.py" "$OUT/dexdump.txt" > "$OUT/methods.txt"
cat "$OUT/env.txt" "$OUT/methods.txt"
echo "(artifacts in $OUT)"
