#!/usr/bin/env python3
"""完美校园 NFC 集成模组 —— 真机自动校验（uiautomator2 / ATX 同源）

流程：重启小米智能卡 -> 打开刷卡页 -> 用 uiautomator 层级定位注入按钮（失败回退日志坐标）
      -> 点击（自动重试）-> 断言已切到完美校园 + 倒计时标记写入
      -> 等待 REVERT 秒断言自动还原、标记清除。

依赖：pip install uiautomator2   （首次会自动往设备推送 u2.jar / AdbKeyboard）
用法：python tools/verify_nfc_flow.py [--serial 3c19ef39] [--revert 30] [--tap-retry 4]
"""

from __future__ import annotations

import argparse
import re
import sys
import time
import xml.etree.ElementTree as ET

try:
    import uiautomator2 as u2
except ImportError:
    sys.exit("缺少依赖：请先 pip install uiautomator2")

MIPAY = "com.miui.tsmclient"
DOUBLE_CLICK = "com.miui.tsmclient/.ui.quick.DoubleClickActivity"
WANMEI_PKG = "com.newcapec.mobile.ncp"
MIPAY_CARD = "com.android.nfc/com.android.nfc.cardemulation.ESEWalletDummyService"

KEY_NFC = "nfc_payment_default_component"
KEY_PENDING = "wanmei_nfc_lsp_pending_restore"

fails: list[str] = []


def step(msg: str) -> None:
    print(f"== {msg}", flush=True)


def ok(msg: str) -> None:
    print(f"  [PASS] {msg}", flush=True)


def bad(msg: str) -> None:
    fails.append(msg)
    print(f"  [FAIL] {msg}", flush=True)


def info(msg: str) -> None:
    print(f"  [info] {msg}", flush=True)


def sh(d: u2.Device, cmd) -> str:
    """兼容不同 uiautomator2 版本：shell() 可能返回 str 或 ShellResponse"""
    try:
        resp = d.shell(cmd)
    except Exception as exc:  # noqa: BLE001
        info(f"shell 失败: {exc}")
        return ""
    if isinstance(resp, str):
        return resp
    out = getattr(resp, "output", None)
    return out if isinstance(out, str) else str(resp)


def parse_bounds(raw: str) -> tuple[int, int, int, int] | None:
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw or "")
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))) if m else None


def find_button(d: u2.Device) -> tuple[int, int] | None:
    """在 UI 层级里找注入按钮：右下角最靠下的可点击 View（宽约屏幕 1/3）"""
    try:
        root = ET.fromstring(d.dump_hierarchy())
    except Exception as exc:  # noqa: BLE001
        info(f"dump_hierarchy 失败：{exc}")
        return None
    w, h = d.window_size()
    best = None
    for node in root.iter("node"):
        if node.get("clickable") != "true":
            continue
        box = parse_bounds(node.get("bounds", ""))
        if not box:
            continue
        left, top, right, bottom = box
        if right - left < w * 0.2 or bottom > h:
            continue
        if left < w * 0.4 or top < h * 0.6:
            continue
        if best is None or bottom > best[1]:
            best = (box, bottom)
    if best is None:
        return None
    left, top, right, bottom = best[0]
    return (left + right) // 2, top + int((bottom - top) * 0.75)


def find_button_from_log(d: u2.Device) -> tuple[int, int] | None:
    # 必须带 -s 标签过滤：否则 grep 会命中 adbd 自己打印的命令行，tail -1 拿到的是回声
    out = sh(d, "logcat -d -s WanmeiNfcLsp:V | grep placeButton | tail -1")
    m = re.search(r"placeButton: left=(\d+) top=(\d+) size=(\d+)x(\d+)", out)
    if not m:
        return None
    left, top, w, h = (int(x) for x in m.groups())
    return left + w // 2, top + int(h * 0.75)


def nfc_default(d: u2.Device) -> str:
    return sh(d, f"settings get secure {KEY_NFC}").strip()


def read_key(d: u2.Device, key: str) -> str:
    return sh(d, f"settings get secure {key}").strip()


def open_card_page(d: u2.Device) -> None:
    sh(d, ["su", "-c", f"am force-stop {MIPAY}"])
    time.sleep(1.5)
    sh(d, "logcat -c")
    sh(d, ["am", "start", "-W", "-a", "com.miui.intent.action.DOUBLE_CLICK", "-n", DOUBLE_CLICK])
    time.sleep(5)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None)
    ap.add_argument("--revert", type=int, default=30, help="自动还原秒数（默认 30）")
    ap.add_argument("--tap-retry", type=int, default=4)
    ap.add_argument("--tolerance", type=int, default=6)
    args = ap.parse_args()

    step("0. 连接设备")
    d = u2.connect(args.serial) if args.serial else u2.connect()
    info(f"device: {d.device_info.get('serial', '?')} {d.device_info.get('model', '')}")
    info(f"模组版本: {sh(d, 'dumpsys package com.mipay.wanmei.lsp | grep versionName').strip()}")
    ok("uiautomator2 已连接")

    step("1. 打开刷卡页并定位注入按钮")
    pos = None
    for attempt in range(1, 4):
        open_card_page(d)
        for _ in range(3):  # u2 服务刚起时第一次 dump 可能还拿不到窗口，短重试
            pos = find_button(d) or find_button_from_log(d)
            if pos:
                break
            time.sleep(1)
        if pos:
            ok(f"第 {attempt} 次尝试定位到按钮 -> 点击 {pos}")
            break
        info(f"第 {attempt} 次未定位到按钮（模组刚升级时 LSPosed 可能要重新优化），重试")
    if not pos:
        bad("未定位到注入按钮：模组没注入小米智能卡进程（检查 LSPosed 作用域 + 重启）")
        print(sh(d, "logcat -d -s WanmeiNfcLsp:V | tail -10"))
        print("\n".join(fails))
        return 1
    cx, cy = pos

    top = sh(d, "dumpsys activity activities | grep topResumedActivity")
    ok("刷卡页在前台") if "tsmclient" in top else bad(f"刷卡页未在前台: {top.strip()}")

    step("2. 点击按钮并断言切卡")
    info(f"点击前默认 NFC: {nfc_default(d)}")
    switched = False
    for i in range(1, args.tap_retry + 1):
        d.click(cx, cy)
        time.sleep(1.8)
        now = nfc_default(d)
        if now.startswith(WANMEI_PKG):
            ok(f"第 {i} 次点击生效，默认 NFC = {now}")
            switched = True
            break
        info(f"第 {i} 次点击未生效（当前 {now}），重试")
    if not switched:
        bad("多次点击后默认 NFC 仍未切到完美校园")

    marker = read_key(d, KEY_PENDING)
    if marker and marker != "null":
        ok(f"倒计时标记已写入: {marker}")
    else:
        bad("倒计时标记未写入，system_server 定时器不会启动（检查 android 作用域）")

    state = sh(d, "logcat -d -s WanmeiNfcLsp:V | grep 状态变化 | tail -1").strip()
    if state:
        ok(f"徽标状态: {state}")

    step(f"3. 等待 {args.revert}s 自动还原")
    started = time.time()
    reverted_at = None
    current = ""
    while time.time() - started < args.revert + args.tolerance:
        time.sleep(1)
        current = nfc_default(d)
        if not current.startswith(WANMEI_PKG):
            reverted_at = round(time.time() - started, 1)
            break
    if reverted_at is None:
        bad(f"{args.revert} 秒后仍未还原（当前 {current}）")
    elif reverted_at < args.revert - 3:
        bad(f"还原过早: {reverted_at}s（应约 {args.revert}s）")
    else:
        ok(f"已自动还原为 {current}（用时 {reverted_at}s）")

    after = read_key(d, KEY_PENDING)
    ok("倒计时标记已清除") if (not after or after == "null") else bad(f"倒计时标记未清除: {after}")
    for line in sh(d, "logcat -d -s WanmeiNfcLsp:V | grep 'auto revert' | tail -3").splitlines():
        info(line)

    step("结果")
    if not fails:
        print("  全部通过", flush=True)
        return 0
    print(f"  失败 {len(fails)} 项", flush=True)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())