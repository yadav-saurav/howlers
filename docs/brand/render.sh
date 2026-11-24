#!/usr/bin/env sh
# Renders the brand PNGs from their HTML/SVG sources with headless Chrome. Run from the repo root after
# `cd frontend && npm ci` (the pages load the Unbounded and Inter fonts from frontend/node_modules/@fontsource*).
#
#   sh docs/brand/render.sh
#
# Override the browser with CHROME=/path/to/chrome. A throwaway profile is created and deleted each run.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
chrome=${CHROME:-"/c/Program Files/Google/Chrome/Application/chrome.exe"}
profile="$here/.chrome-profile"
rm -rf "$profile"

to_url() {
  # file:// URL for a path; on Git Bash convert /c/... to C:/...
  p=$1
  if command -v cygpath >/dev/null 2>&1; then p=$(cygpath -m "$p"); fi
  echo "file:///$p" | sed 's#file:////#file:///#'
}
to_native() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}

shot() { # page width height output
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --user-data-dir="$(to_native "$profile")" --allow-file-access-from-files \
    --virtual-time-budget=3000 --window-size="$2,$3" \
    --screenshot="$(to_native "$here/$4")" "$(to_url "$here/$1")" >/dev/null 2>&1
  echo "wrote docs/brand/$4"
}

shot social-preview.html 1280 640 social-preview.png
shot logo.html 512 512 logo-512.png
rm -rf "$profile"
