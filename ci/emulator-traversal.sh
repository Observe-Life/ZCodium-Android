#!/usr/bin/env bash
# ZCodium 安卓「全功能遍历」引擎 v2：数据驱动（ci/cases.tsv），双截图取证（定位/核验），
# 单行实时案卷日志，结果逐条落 screenshots/results.jsonl + <编号>-data.json。
# 用法：bash ci/emulator-traversal.sh "<手机待填网址>" [apk路径]
# 环境变量：ZP_GROUPS=A,H,W,... 只跑指定组（默认全跑）
# 注意：不用 heredoc（CI 行尾处理不稳）；python 一律单引号多行 -c 参数。
set -u
URL="${1:-}"
APK="${2:-}"
[ -n "$APK" ] || { [ -f ZCodium-debug.apk ] && APK=ZCodium-debug.apk; }
[ -n "$APK" ] || APK=ZCodium.apk
[ -n "$URL" ] || { echo "[FAIL] 未提供待填网址"; exit 1; }
[ -f "$APK" ] || { echo "[FAIL] 找不到安装包 $APK"; exit 1; }
cd "$(dirname "$0")/.." || exit 1
export ZP_PKG=app.zcodium.remote
SHOTS=screenshots; CASES=ci/cases.tsv
mkdir -p "$SHOTS"
: > "$SHOTS/results.jsonl"
PASS=0; FAILN=0; BLOCK=0; TOTAL=0

say()  { echo "[traversal] $*"; }

# ---------- L1 原生壳探针 ----------
dump_ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null; }
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
find_node() { printf '%s' "$1" | python3 -c "$PY_FIND" "$2" "$3"; }
has_text() { find_node "$(dump_ui)" text "$1" >/dev/null 2>&1; }
ntext() { has_text "$1"; }
ntap() { local xy; xy=$(find_node "$(dump_ui)" text "$1") || return 1; adb shell input tap $xy; }
ntapc() { local xy; xy=$(find_node "$(dump_ui)" class "$1") || return 1; adb shell input tap $xy; }
# 长串 input text 会因事件队列溢出丢字（首轮 URL 只剩尾巴的根因）——分段发送，每段15字符间隔0.4s
ntype() {
  local s="$1" i=0 chunk
  while [ "$i" -lt "${#s}" ]; do
    chunk="${s:$i:15}"
    adb shell "input text '$chunk'" >/dev/null 2>&1
    i=$((i + 15))
    sleep 0.4
  done
}

# ---------- L2 网页探针（CDP） ----------
probe() { python3 ci/web-probe.py "$@"; }

# ---------- L3 系统旁证 ----------
back() { adb shell input keyevent 4 >/dev/null 2>&1; sleep 1; }
amstart() { adb shell am start -W -n app.zcodium.remote/.MainActivity >/dev/null 2>&1; sleep 4; }
focus() { adb shell dumpsys window 2>/dev/null | grep -q 'mCurrentFocus.*zcodium.remote'; }
lcat() { adb logcat -d -t 500 | grep -qF "$1"; }
lcatneg() { ! adb logcat -d -t 800 | grep -qE "FATAL EXCEPTION|AndroidRuntime: process: app.zcodium.remote"; }
svccheck() { adb shell dumpsys activity services app.zcodium.remote 2>/dev/null | grep -q zcodium.remote; }

reset_ui() {
  if focus; then back; else amstart; fi
}
shot() { adb exec-out screencap -p > "$SHOTS/$1.png" 2>/dev/null; }

sanitize() { tr -d '\r' | tr '\n' ' ' | tr '"' "'" | head -c 220; }
record() { # $1=id $2=desc $3=verdict $4=measured
  local line
  line=$(printf '{"id":"%s","desc":"%s","verdict":"%s","measured":"%s"}' "$1" "$(printf '%s' "$2" | tr '"' "'")" "$3" "$(printf '%s' "$4" | tr '"' "'")")
  printf '%s\n' "$line" >> "$SHOTS/results.jsonl"
  printf '%s\n' "$line" > "$SHOTS/$1-data.json"
}

in_groups() { [ -z "${ZP_GROUPS:-}" ] && return 0; printf ',%s,' "$ZP_GROUPS" | tr -d ' ' | grep -qF ",$1,"; }

# ---------- 前置：依赖与安装 ----------
python3 -c "import websocket" 2>/dev/null || pip3 install --quiet websocket-client || pip3 install --quiet --break-system-packages websocket-client
say "安装包=$APK 待填网址=$URL"
adb install -r "$APK" >/dev/null 2>&1 || { echo "[FAIL] APK 安装失败"; exit 1; }
adb shell pm clear app.zcodium.remote >/dev/null 2>&1
amstart

# ---------- 主循环：逐条用例 ----------
# 必须用 fd3 读表：循环体内 adb/python 会继承 stdin，直接 read <文件 会被子进程吞掉剩余行（上一轮只跑1条的根因）
EXPECTED=$(awk -F'\t' -v g="${ZP_GROUPS:-}" 'BEGIN{n=split(g,a,",");for(i=1;i<=n;i++)ok[a[i]]=1} !/^#/ && NF==8 && (g==""||($1 in ok)){c++} END{print c+0}' "$CASES")
say "用例表共 $EXPECTED 条"
while IFS=$'\t' read -r grp id desc waits setup locate action verify <&3; do
  case "$grp" in ''|'#'*) continue;; esac
  in_groups "$grp" || continue
  TOTAL=$((TOTAL+1))
  eval "$setup" >/dev/null 2>&1 || true
  shot "$id-定位"
  lok=0; try=0
  while [ "$try" -lt 3 ]; do
    try=$((try+1))
    if eval "$locate" >/tmp/zp.locate 2>&1; then lok=1; break; fi
    echo "[$id] STAGE=locate try=$try 实测=[$(sanitize </tmp/zp.locate)] 判定=RETRY"
    reset_ui
  done
  if [ "$lok" -ne 1 ]; then
    BLOCK=$((BLOCK+1)); shot "$id-阻塞"
    echo "[$id] STAGE=locate 判定=BLOCKED（$desc）"
    record "$id" "$desc" "BLOCKED" "$(sanitize </tmp/zp.locate)"
    continue
  fi
  if [ "$action" != "none" ]; then
    eval "$action" >/tmp/zp.act 2>&1
    echo "[$id] STAGE=action 实测=[$(sanitize </tmp/zp.act)] 判定=DONE"
  fi
  sleep "${waits:-3}"
  shot "$id-核验"
  if eval "$verify" >/tmp/zp.verify 2>&1; then
    PASS=$((PASS+1)); verdict=PASS
  else
    FAILN=$((FAILN+1)); verdict=FAIL
  fi
  meas=$(sanitize </tmp/zp.verify)
  echo "[$id] STAGE=verify 实测=[$meas] 期望=[$(printf '%s' "$verify" | head -c 90)] 判定=$verdict"
  record "$id" "$desc" "$verdict" "$meas"
done 3< "$CASES"

if [ "$TOTAL" -ne "$EXPECTED" ]; then
  echo "[engine] STAGE=final 实测=实际执行=$TOTAL 期望=$EXPECTED 判定=FAIL（读表被打断，禁止假绿）"
  FAILN=$((FAILN+1))
fi

# ---------- 收尾 ----------
echo "== 崩溃终检 =="
if lcatneg; then echo "[S-0] STAGE=verify 实测=[clean] 判定=PASS"; else
  FAILN=$((FAILN+1)); echo "[S-0] STAGE=verify 实测=[crash found] 判定=FAIL"
  adb logcat -d -t 200 | grep -A 20 "FATAL EXCEPTION" | head -40
fi
echo "=================== 遍历汇总 ==================="
echo "总计=$TOTAL PASS=$PASS FAIL=$FAILN BLOCKED=$BLOCK"
if [ "$FAILN" -gt 0 ] || [ "$BLOCK" -gt 0 ]; then
  echo "RESULT: FAIL（明细见 screenshots/results.jsonl 与双截图）"
  exit 1
fi
echo "RESULT: PASS（$TOTAL 项全过，每项目含定位/核验双截图与数据）"
exit 0
