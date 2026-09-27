#!/usr/bin/env bash
# ZCodium 安卓端「全功能遍历」测试脚本（无头安卓16模拟器 + 与电脑端公网联调）
# 用法：bash ci/emulator-traversal.sh "<手机待填网址>"
# 说明：通过 uiautomator dump 解析界面元素、adb input 驱动界面；每步截图；末尾查崩溃日志。
# 注意：本脚本不使用 heredoc（CI 环境对多行 heredoc 的行尾处理不稳），python 一律用单引号包裹的多行 -c 参数。
set -u

URL="${1:-}"
SHOTS="screenshots"
mkdir -p "$SHOTS"
STEP=0
FAILED=0

say()  { echo "[traversal] $*"; }
ok()   { echo "[OK]   $*"; }
fail() { echo "[FAIL] $*"; FAILED=1; }

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null
}

# 在 UI XML 中查找节点（kind=text: 匹配 text/content-desc 子串；kind=class: 匹配 class 全名），输出中心坐标
PY_FIND='
import sys, re
import xml.etree.ElementTree as ET
kind, needle = sys.argv[1], sys.argv[2]
try:
    root = ET.fromstring(sys.stdin.read())
except Exception:
    sys.exit(1)
for n in root.iter():
    if kind == "text":
        hit = needle in (n.get("text") or "") or needle in (n.get("content-desc") or "")
    else:
        hit = needle == (n.get("class") or "")
    if hit:
        m = re.findall(r"\d+", n.get("bounds") or "")
        if len(m) == 4:
            print((int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2)
            sys.exit(0)
sys.exit(1)
'

find_node() {
  local xml="$1" kind="$2" needle="$3"
  printf '%s' "$xml" | python3 -c "$PY_FIND" "$kind" "$needle"
}

tap_text() {
  local needle="$1"
  local xy
  xy=$(find_node "$(dump_ui)" text "$needle") || return 1
  say "tap '$needle' at $xy"
  adb shell input tap $xy
  return 0
}

tap_class() {
  local cls="$1"
  local xy
  xy=$(find_node "$(dump_ui)" class "$cls") || return 1
  say "tap <$cls> at $xy"
  adb shell input tap $xy
  return 0
}

has_text() { find_node "$(dump_ui)" text "$1" >/dev/null 2>&1; }

expect_text() {
  if has_text "$1"; then ok "界面出现「$1」"; else fail "界面未出现「$1」"; fi
}

shot() {
  STEP=$((STEP + 1))
  adb exec-out screencap -p > "$SHOTS/$(printf '%02d' "$STEP")-$1.png" 2>/dev/null
}

if [ -z "$URL" ]; then
  fail "未提供待填网址（workflow 输入 phone_url 为空）"
  exit 1
fi
say "待填网址 = $URL"

echo "== 1. 安装并启动 =="
adb install -r ZCodium.apk || { fail "APK 安装失败"; exit 1; }
ok "APK 已安装"
adb shell pm clear app.zcodium.remote >/dev/null 2>&1
adb shell am start -W -n app.zcodium.remote/.MainActivity >/dev/null 2>&1
sleep 8
expect_text "ZCodium"
expect_text "连接"
shot "launch"

echo "== 2. 填入网址 =="
if tap_class "android.widget.EditText"; then
  sleep 1
  adb shell input text "$(printf '%s' "$URL" | sed 's/ /%s/g')"
  sleep 1
  ok "已填入网址"
else
  fail "未找到网址输入框"
fi
shot "url-filled"

echo "== 3. 连接（经公网隧道直连电脑端）=="
tap_text "连接" || fail "未找到「连接」按钮"
sleep 25
if has_text "刷新" || has_text "已连接到电脑智能体"; then
  ok "页面已连接（出现工具栏/连接状态）"
else
  fail "连接后未见工具栏，页面可能未加载"
fi
shot "connected"

echo "== 4. 会话列表 =="
if has_text "工作区"; then ok "工作区区块出现"; else say "（未识别到工作区文案，继续）"; fi
shot "workspace-list"

echo "== 5. 刷新 =="
tap_text "刷新" && sleep 5 && ok "刷新已点击" || fail "未找到「刷新」"
shot "after-refresh"

echo "== 6. 搜索 =="
if tap_text "搜索"; then
  sleep 5
  if has_text "取消" || has_text "搜索"; then ok "搜索界面已打开"; else fail "搜索界面未出现"; fi
  shot "search"
  adb shell input keyevent 4 >/dev/null 2>&1
  sleep 3
else
  fail "未找到「搜索」"
fi

echo "== 7. 新建会话 =="
if tap_text "新建"; then
  sleep 8
  if has_text "发送" || has_text "向 ZCode 提问" || has_text "继续输入"; then
    ok "新建会话界面已打开"
  else
    fail "新建后未见会话输入界面"
  fi
  shot "new-session"
  adb shell input keyevent 4 >/dev/null 2>&1
  sleep 3
else
  fail "未找到「新建」"
fi

echo "== 8. 远控对话框 =="
if tap_text "远控"; then
  sleep 4
  if has_text "取消" || has_text "连接"; then ok "远控对话框已弹出"; else fail "远控对话框未出现"; fi
  shot "remote-dialog"
  tap_text "取消" >/dev/null 2>&1 || adb shell input keyevent 4 >/dev/null 2>&1
  sleep 3
else
  fail "未找到「远控」"
fi

echo "== 9. 主题 =="
if tap_text "主题"; then
  sleep 5
  ok "主题按钮已点击"
  shot "theme"
  adb shell input keyevent 4 >/dev/null 2>&1
  sleep 3
else
  fail "未找到「主题」"
fi

echo "== 10. 稳定性复查 =="
sleep 5
if has_text "刷新" || has_text "新建"; then ok "多轮操作后界面仍存活"; else fail "多轮操作后界面异常"; fi
shot "final"

echo "== 11. 崩溃检查 =="
if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: process: app.zcodium.remote" >/dev/null 2>&1; then
  fail "检测到应用崩溃日志"
  adb logcat -d -t 200 | grep -A 20 "FATAL EXCEPTION" | head -40
else
  ok "无崩溃日志"
fi

echo "=================== 遍历测试结果 ==================="
if [ "$FAILED" -eq 0 ]; then
  echo "RESULT: PASS（全部功能遍历通过）"
else
  echo "RESULT: FAIL（存在未通过项，见上方 [FAIL]）"
fi
echo "截图为证：$SHOTS/"
exit "$FAILED"
