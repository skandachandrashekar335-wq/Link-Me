#!/usr/bin/env bash
set -euo pipefail

VERSION="v2.1.0"
RELEASE_BASE="https://github.com/skandachandrashekar335-wq/Link-Me/releases/download/${VERSION}"
CORE_APK="AndroidBridge-${VERSION}.apk"
ADDON_APK="AndroidBridge-NotifyAddon-${VERSION}.apk"

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
ADDON_PATH="$(resolve_apk "$ADDON_APK")"

echo "Waiting for USB-connected Android device (USB Debugging enabled)..."
"$ADB_BIN" wait-for-device
"$ADB_BIN" devices

echo "Installing ${CORE_APK} (bypasses Play Protect prompts over USB)..."
"$ADB_BIN" install -r -g "$CORE_PATH" || "$ADB_BIN" install -r "$CORE_PATH"

echo "Installing ${ADDON_APK}..."
"$ADB_BIN" install -r -g "$ADDON_PATH" || "$ADB_BIN" install -r "$ADDON_PATH"

echo ""
echo "==========================================================="
echo " Done! Both Link-Me APKs (${VERSION}) installed cleanly."
echo " Open AndroidBridge -> Scan QR -> Point at MacBridge QR."
echo "==========================================================="
read -r -p "Press Enter to close..."
