#!/usr/bin/env bash
# 无头安卓16模拟器测试入口（由 workflow 单行调用）
# 用法：bash ci/emulator-run.sh "<手机待填网址>"
#   有网址 → 全功能遍历（含与电脑端公网联调）
#   无网址 → 基础冒烟（装机/启动/深链/截图/崩溃检查）
set -u
URL="${1:-}"
cd "$(dirname "$0")/.." || exit 1

if [ -n "$URL" ]; then
  echo "== 模式：全功能遍历（Android 16 无头模拟器 + 电脑端公网联调）=="
  exec bash ci/emulator-traversal.sh "$URL"
fi

echo "== 模式：基础冒烟（未提供 phone_url）=="
mkdir -p screenshots
adb install -r ZCodium.apk || exit 1
adb shell am start -W -n app.zcodium.remote/.MainActivity
sleep 12
adb shell pidof app.zcodium.remote || { adb logcat -d -t 300; exit 1; }
adb exec-out screencap -p > screenshots/01-main.png
adb shell am start -a android.intent.action.VIEW -d 'zcode://session?sess=smoke' -n app.zcodium.remote/.MainActivity
sleep 6
adb exec-out screencap -p > screenshots/02-deeplink.png
if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: process: app.zcodium.remote" >/dev/null 2>&1; then
  echo "crash detected"
  adb logcat -d -t 200 | grep -A 20 "FATAL EXCEPTION" | head -40
  exit 1
fi
echo "smoke passed"
