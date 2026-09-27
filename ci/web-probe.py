#!/usr/bin/env python3
# ZCodium CI 探针引擎（L2 层）：经 CDP 调试协议进入 WebView 内部，查询元素/读数据/合成触摸/断言。
# 依赖：仅 websocket-client（CI runner 内 pip 安装；本机零安装）。App 侧需 debug 包（开了网页调试开关）。
# 用法：python3 ci/web-probe.py <子命令> <参数...>
#   wait   <css> <timeout>          轮询元素存在；命中 exit0 打印命中数；超时 exit1
#   assert <label> <js> <timeout>   轮询 js 表达式为真；exit0/1，末值进日志
#   get    <js>                     求值一次并打印（JSON 序列化）
#   count  <css>                    打印匹配数量（异常时打印 0）
#   text   <css>                    打印首个元素的 textContent（截 300 字）
#   attr   <css> <name>             打印首个元素的属性值
#   tap    <css>                    scrollIntoView 后在元素中心合成真实触摸（React 监听可收到）
#   click  <css>                    直接 el.click()（备用：合成触摸不生效时）
#   type   <css> <text>             聚焦输入框并插入文本
#   press  <css>                    在元素上派发 Enter 键事件序列（keydown/keypress/keyup，触发提交）
#   errors <seconds>                监听 N 秒控制台错误/异常/错误级日志；errors=N，有错 exit1
#   url                             打印 location.href
# 环境变量 ZP_PKG 可覆盖包名（默认 app.zcodium.remote）。
import json
import os
import subprocess
import sys
import time
import urllib.request

import websocket  # websocket-client

PKG = os.environ.get("ZP_PKG", "app.zcodium.remote")
PORT = 9222


def adb(*args):
    r = subprocess.run(("adb",) + args, capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        raise RuntimeError("adb " + " ".join(args) + " -> " + (r.stderr or r.stdout).strip()[:200])
    return (r.stdout or "").strip()


def connect():
    """找到可调试页面的 WebSocket 地址：遍历 pidof 逐个试套接字，挑远控页。"""
    try:
        pids = adb("shell", "pidof", PKG).split()
    except Exception:
        pids = []
    for pid in pids:
        try:
            adb("forward", "tcp:%d" % PORT, "localabstract:webview_devtools_remote_%s" % pid)
            with urllib.request.urlopen("http://127.0.0.1:%d/json" % PORT, timeout=3) as resp:
                targets = json.load(resp)
        except Exception:
            continue
        pages = [t for t in targets if t.get("type") == "page"]
        for t in pages:
            url = t.get("url", "")
            if "/remote/v4" in url or "/web-remote" in url:
                return t["webSocketDebuggerUrl"]
        if pages:
            return pages[0]["webSocketDebuggerUrl"]
    raise RuntimeError("找不到可调试的 WebView 页面（确认装的是 debug 包且页面已加载）")


class CDP(object):
    def __init__(self):
        # suppress_origin：WebView DevTools 套接字会拒绝带 Origin 头的 WebSocket 握手（403 Rejected）
        self.ws = websocket.create_connection(connect(), timeout=15, max_size=32 * 1024 * 1024, suppress_origin=True)
        self.mid = 0

    def cmd(self, method, params=None, timeout=20):
        self.mid += 1
        mid = self.mid
        self.ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
        deadline = time.time() + timeout
        while time.time() < deadline:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(msg["error"].get("message", "cdp error"))
                return msg.get("result", {})
        raise TimeoutError(method)

    def evaluate(self, expr):
        r = self.cmd("Runtime.evaluate", {"expression": expr, "returnByValue": True})
        if r.get("exceptionDetails"):
            raise RuntimeError("JS异常:" + str(r["exceptionDetails"].get("text", ""))[:160])
        return r.get("result", {}).get("value")


def j(x):
    return json.dumps(x, ensure_ascii=False)


def qs(sel):
    return "document.querySelector(%s)" % j(sel)


def cmd_wait(c, sel, t):
    deadline = time.time() + float(t)
    while time.time() < deadline:
        try:
            n = c.evaluate("%s?document.querySelectorAll(%s).length:0" % (qs(sel), j(sel)))
            if n:
                print("found=%s count=%s" % (sel, n))
                return 0
        except Exception:
            pass
        time.sleep(0.5)
    print("found=%s timeout" % sel)
    return 1


def cmd_assert(c, label, expr, t):
    deadline = time.time() + float(t)
    last = None
    while time.time() < deadline:
        try:
            last = c.evaluate(expr)
            if last:
                print("ASSERT %s OK last=%s" % (label, j(last)[:200]))
                return 0
        except Exception as e:
            last = str(e)[:120]
        time.sleep(0.5)
    print("ASSERT %s FAIL last=%s" % (label, j(last)[:200]))
    return 1


def cmd_get(c, expr):
    print(j(c.evaluate(expr)))
    return 0


def cmd_count(c, sel):
    try:
        print(c.evaluate("%s?document.querySelectorAll(%s).length:0" % (qs(sel), j(sel))))
    except Exception:
        print(0)
    return 0


def cmd_text(c, sel):
    v = c.evaluate("%s?(%s).textContent.slice(0,300):null" % (qs(sel), qs(sel)))
    print(j(v))
    return 0


def cmd_attr(c, sel, name):
    v = c.evaluate("%s?(%s).getAttribute(%s):null" % (qs(sel), qs(sel), j(name)))
    print(j(v))
    return 0


def cmd_tap(c, sel):
    box = c.evaluate(
        "(()=>{const e=document.querySelector(%s);if(!e)return null;"
        "e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();"
        "return {x:r.x+r.width/2,y:r.y+r.height/2};})()" % j(sel)
    )
    if not box:
        print("TAP %s 元素不存在" % sel)
        return 3
    c.cmd("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [{"x": box["x"], "y": box["y"]}]})
    time.sleep(0.08)
    c.cmd("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
    print("TAP %s at %s,%s" % (sel, box["x"], box["y"]))
    return 0


def cmd_click(c, sel):
    v = c.evaluate("(()=>{const e=document.querySelector(%s);if(!e)return false;"
                   "e.scrollIntoView({block:'center'});e.click();return true;})()" % j(sel))
    print("CLICK %s %s" % (sel, "OK" if v else "元素不存在"))
    return 0 if v else 3


def cmd_type(c, sel, text):
    ok = c.evaluate("(()=>{const e=document.querySelector(%s);if(!e)return false;e.focus();return true;})()" % j(sel))
    if not ok:
        print("TYPE %s 输入框不存在" % sel)
        return 3
    c.cmd("Input.insertText", {"text": text})
    print("TYPE %s len=%s" % (sel, len(text)))
    return 0


def cmd_press(c, sel):
    v = c.evaluate("(()=>{const e=document.querySelector(%s)||document.activeElement;if(!e)return false;"
                   "for(const t of ['keydown','keypress','keyup']){"
                   "e.dispatchEvent(new KeyboardEvent(t,{bubbles:true,cancelable:true,key:'Enter',"
                   "code:'Enter',keyCode:13,which:13}));}return true;})()" % j(sel))
    print("PRESS %s %s" % (sel, "OK" if v else "无可聚焦元素"))
    return 0 if v else 3


def cmd_url(c):
    print(c.evaluate("location.href"))
    return 0


def main():
    a = sys.argv[1:]
    if not a:
        print("用法见文件头注释")
        return 2
    handlers = {
        "wait": lambda c: cmd_wait(c, a[1], a[2] if len(a) > 2 else 15),
        "assert": lambda c: cmd_assert(c, a[1], a[2], a[3] if len(a) > 3 else 15),
        "get": lambda c: cmd_get(c, a[1]),
        "count": lambda c: cmd_count(c, a[1]),
        "text": lambda c: cmd_text(c, a[1]),
        "attr": lambda c: cmd_attr(c, a[1], a[2]),
        "tap": lambda c: cmd_tap(c, a[1]),
        "click": lambda c: cmd_click(c, a[1]),
        "type": lambda c: cmd_type(c, a[1], a[2]),
        "press": lambda c: cmd_press(c, a[1]),
        "errors": lambda c: cmd_errors(c, a[1] if len(a) > 1 else 3),
        "url": lambda c: cmd_url(c),
    }
    fn = handlers.get(a[0])
    if not fn:
        print("未知子命令 " + a[0])
        return 2
    try:
        c = CDP()
        return fn(c)
    except Exception as e:
        print("PROBE-ERROR " + str(e)[:240])
        return 1


if __name__ == "__main__":
    sys.exit(main())
