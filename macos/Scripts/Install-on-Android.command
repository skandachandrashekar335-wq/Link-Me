#!/usr/bin/env bash
set -euo pipefail

VERSION="v3.0.0"
RELEASE_BASE="https://github.com/skandachandrashekar335-wq/Link-Me/releases/download/${VERSION}"
CORE_APK="AndroidBridge-${VERSION}.apk"

export PATH="/opt/homebrew/bin:/usr/local/bin:${HOME}/Library/Android/sdk/platform-tools:${PATH}"
ADB_BIN="$(command -v adb || true)"
if [ -z "$ADB_BIN" ]; then
  echo "==========================================================="
  echo " adb was not found on this Mac."
  echo " Install Android Platform Tools with Homebrew:"
  echo "   brew install --cask android-platform-tools"
  echo " Then re-run this script with your Android phone plugged in."
  echo "==========================================================="
  read -r -p "Press Enter to exit..."
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK_DIR="${HOME}/Downloads/Link-Me-Android-${VERSION}"
mkdir -p "$WORK_DIR"

resolve_apk() {
  local name="$1"
  if [ -f "${SCRIPT_DIR}/${name}" ]; then
    echo "${SCRIPT_DIR}/${name}"
  elif [ -f "${HOME}/Downloads/${name}" ]; then
    echo "${HOME}/Downloads/${name}"
  elif [ -f "${WORK_DIR}/${name}" ]; then
    echo "${WORK_DIR}/${name}"
  else
    echo "${WORK_DIR}/${name}"
    >&2 echo "Downloading ${name} from ${RELEASE_BASE}..."
    curl -fSL "${RELEASE_BASE}/${name}" -o "${WORK_DIR}/${name}"
  fi
}

CORE_PATH="$(resolve_apk "$CORE_APK")"

echo "Checking USB-connected Android device (USB Debugging enabled)..."
DEVICE_SERIALS=()
while IFS= read -r serial; do
  [ -n "$serial" ] && DEVICE_SERIALS+=("$serial")
done < <("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')

if [ "${#DEVICE_SERIALS[@]}" -eq 0 ]; then
  echo "Waiting for Android device..."
  "$ADB_BIN" wait-for-device
  while IFS= read -r serial; do
    [ -n "$serial" ] && DEVICE_SERIALS+=("$serial")
  done < <("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')
fi

"$ADB_BIN" devices

for SERIAL in "${DEVICE_SERIALS[@]}"; do
  echo "Installing ${CORE_APK} on ${SERIAL}..."
  "$ADB_BIN" -s "$SERIAL" install -r -g "$CORE_PATH" || "$ADB_BIN" -s "$SERIAL" install -r "$CORE_PATH"
done

echo ""
echo "==========================================================="
echo " Done! Link-Me APK (${VERSION}) installed cleanly."
echo " Open AndroidBridge -> Scan QR -> Point at MacBridge QR."
echo "==========================================================="
read -r -p "Press Enter to close..."
