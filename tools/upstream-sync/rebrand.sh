#!/usr/bin/env bash
# Mechanically rebrand an HMA-OSS checkout into F-U Query Package.
# Usage: rebrand.sh <repo-dir>   (operates on the git index + worktree there)
set -euo pipefail
cd "$1"

move_tree() {
  local src="$1" dst="$2"
  [ -d "$src" ] || return 0
  while IFS= read -r f; do
    local rel="${f#"$src"/}"
    mkdir -p "$dst/$(dirname "$rel")"
    git mv -k "$f" "$dst/$rel"
  done < <(git ls-files "$src")
}

for mod in app common zygote stub; do
  for kind in java aidl; do
    move_tree "$mod/src/main/$kind/icu/nullptr/hidemyapplist" "$mod/src/main/$kind/com/iodvd/fuqp"
    move_tree "$mod/src/main/$kind/org/frknkrc44/hma_oss"     "$mod/src/main/$kind/com/iodvd/fuqp"
  done
done
find . -path ./.git -prune -o -type d -empty -print 2>/dev/null | grep -E '/(icu|org)(/|$)' | xargs -r rmdir -p 2>/dev/null || true

# LogAdapter sat in .../adapter/ while declaring ...ui.adapter
[ -f app/src/main/java/com/iodvd/fuqp/adapter/LogAdapter.kt ] && {
  mkdir -p app/src/main/java/com/iodvd/fuqp/ui/adapter
  git mv app/src/main/java/com/iodvd/fuqp/adapter/LogAdapter.kt app/src/main/java/com/iodvd/fuqp/ui/adapter/LogAdapter.kt
}

# file renames that track class renames
while IFS= read -r f; do
  nf="${f//IHMAService/IFUQPService}"; nf="${nf//HMAService/FUQPService}"
  [ "$f" != "$nf" ] && git mv "$f" "$nf"
done < <(git ls-files | grep -E 'HMAService')
[ -f HideMyAss-OSS.svg ] && git mv HideMyAss-OSS.svg FUQP.svg

mapfile -t TXT < <(git ls-files '*.kt' '*.java' '*.aidl' '*.xml' '*.kts' '*.pro' '*.sh' '*.md' \
  '*.yml' '*.yaml' '*.txt' '*.toml' '*.properties' '*.json')

sed -i \
  -e 's#org\.frknkrc44\.hma_oss#com.iodvd.fuqp#g' \
  -e 's#icu\.nullptr\.hidemyapplist#com.iodvd.fuqp#g' \
  -e 's#org/frknkrc44/hma_oss#com/iodvd/fuqp#g' \
  -e 's#icu/nullptr/hidemyapplist#com/iodvd/fuqp#g' \
  -e 's#IHMAService#IFUQPService#g' \
  -e 's#HMAService#FUQPService#g' \
  -e 's#\bhmaApp\b#fuqpApp#g' \
  "${TXT[@]}"
