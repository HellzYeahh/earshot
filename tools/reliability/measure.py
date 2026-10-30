"""Reliability measurements on a real phone over adb (docs/reliability.md, 1.0 plan item 3).

    python tools/reliability/measure.py connect              # connect timings in the current logcat
    python tools/reliability/measure.py battery              # Earshot's battery use, wake locks, alarms
    python tools/reliability/measure.py alive                # is the process and its service running
    python tools/reliability/measure.py startup [--runs 5]   # cold start time (phone must be unlocked)
    python tools/reliability/measure.py cycles [--runs 10]   # force-stop + start N times, time each
                                                             # connect (drops the headset's control link!)

Read-only except `startup` and `cycles`, which restart Earshot. Nothing is reset on the phone:
battery numbers are since the last full charge, as Android keeps them. Use `--serial` when more
than one device is attached and `--package dev.earshot.foss` for the GitHub build.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import statistics
import subprocess
import sys
import time
from datetime import datetime

LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+([VDIWEF])/(Earshot/[\w/]+)\(\s*\d+\):\s?(.*)$")


def adb_path() -> str:
    found = shutil.which("adb")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    exe = os.path.join(sdk, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
    if not os.path.isfile(exe):
        sys.exit("adb not found (set ANDROID_HOME)")
    return exe


class Phone:
    def __init__(self, serial: str | None, package: str):
        self.adb = adb_path()
        self.package = package
        if serial is None:
            out = subprocess.run([self.adb, "devices"], capture_output=True, text=True).stdout
            devices = [l.split("\t")[0] for l in out.splitlines()[1:] if l.endswith("\tdevice") and not l.startswith("emulator-")]
            if not devices:
                sys.exit("no phone attached (adb devices)")
            serial = devices[0]
        self.serial = serial

    def sh(self, *args: str, timeout: int = 60) -> str:
        return subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=timeout).stdout

    def logcat(self) -> list[tuple[datetime, str, str]]:
        out = self.sh("logcat", "-d", "-v", "time", timeout=120)
        year = datetime.now().year
        rows = []
        for line in out.splitlines():
            m = LINE.match(line)
            if m:
                rows.append((datetime.strptime(f"{year}-{m.group(1)}", "%Y-%m-%d %H:%M:%S.%f"), m.group(3), m.group(4)))
        return rows


def connect_attempts(rows):
    """One attempt per 'BMAP candidates' line: time to the socket, to the first BMAP answer, or why it failed."""
    attempts, cur = [], None
    for at, tag, msg in rows:
        if tag != "Earshot/Bt":
            continue
        if msg.startswith("BMAP candidates"):
            if cur:
                attempts.append(cur)
            cur = {"start": at, "socket": None, "answer": None, "channel": None, "failed": None}
        elif cur is None:
            continue
        elif msg.startswith("connected to") and cur["socket"] is None:
            cur["socket"] = at
            cur["channel"] = msg.split(" via ")[-1] if " via " in msg else None
        elif msg.startswith("BMAP answers on") and cur["answer"] is None:
            cur["answer"] = at
        elif re.search(r"\b(busy|failed|gave up|no channel|unresponsive)\b", msg, re.I) and cur["answer"] is None and cur["failed"] is None:
            cur["failed"] = msg[:100]
    if cur:
        attempts.append(cur)
    return attempts


def cmd_connect(phone: Phone, _args) -> None:
    attempts = connect_attempts(phone.logcat())
    if not attempts:
        print("No connect attempt in the current logcat (it only holds the last few minutes to hours).")
        return
    ok = [a for a in attempts if a["answer"]]
    for a in attempts:
        if a["answer"]:
            print(f"{a['start']:%H:%M:%S}  socket {secs(a['start'], a['socket'])}  first answer {secs(a['start'], a['answer'])}  ({a['channel']})")
        else:
            print(f"{a['start']:%H:%M:%S}  NO ANSWER  {a['failed'] or 'no answer in the log yet'}")
    if ok:
        times = [(a["answer"] - a["start"]).total_seconds() for a in ok]
        print(f"\n{len(ok)}/{len(attempts)} connected; first answer median {statistics.median(times):.2f} s, worst {max(times):.2f} s")


def secs(a, b) -> str:
    return f"{(b - a).total_seconds():.2f} s" if b else "-"


def cmd_battery(phone: Phone, _args) -> None:
    uid_line = phone.sh("shell", "pm", "list", "packages", "-U", phone.package)
    m = re.search(r"uid:(\d+)", uid_line)
    if not m:
        sys.exit(f"{phone.package} is not installed")
    uid = int(m.group(1))
    app_uid = f"u0a{uid - 10000}"
    stats = phone.sh("shell", "dumpsys", "batterystats", "--charged", phone.package, timeout=120)
    since = re.search(r"Time on battery: ([^\n(]+)", stats)
    power = re.search(rf"uid {app_uid}: ([\d.]+)", stats, re.I)
    print(f"{phone.package} ({app_uid}), since the last full charge")
    print(f"  on battery:            {since.group(1).strip() if since else '?'}")
    print(f"  estimated power use:   {power.group(1) + ' mAh' if power else '? (not in the estimate)'}")
    for label, pat in [("wake locks", r"Wake lock \S+ ?(.*?)realtime"), ("foreground service", r"Foreground services: ([^\n]+)"),
                       ("Bluetooth scans", r"Bluetooth Scan: ([^\n]+)"), ("alarms (wakeups)", r"Wakeup alarm \*walarm\*:\S+: (\d+) times")]:
        found = re.findall(pat, stats)
        if found:
            print(f"  {label}: {found[:6]}")
    alarms = phone.sh("shell", "dumpsys", "alarm")
    pending = len(re.findall(rf"\b{re.escape(phone.package)}\b.*\n.*tag=", alarms))
    print(f"  alarms pending now:    {pending}")


def cmd_alive(phone: Phone, _args) -> None:
    pid = phone.sh("shell", "pidof", phone.package).strip()
    services = phone.sh("shell", "dumpsys", "activity", "services", phone.package)
    running = re.findall(r"ServiceRecord\{[^}]* ([\w.$/]+)\}", services)
    fg = "isForeground=true" in services
    print(f"process: {'pid ' + pid if pid else 'not running'}")
    print(f"services: {sorted(set(running)) or 'none'}{' (foreground)' if fg else ''}")


def cmd_startup(phone: Phone, args) -> None:
    if "isKeyguardShowing=true" in phone.sh("shell", "dumpsys", "window"):
        sys.exit("unlock the phone first: a locked phone doesn't draw the app, so the time means nothing")
    times = []
    for i in range(args.runs):
        phone.sh("shell", "am", "force-stop", phone.package)
        time.sleep(2)
        out = phone.sh("shell", "am", "start", "-W", "-n", f"{phone.package}/dev.earshot.MainActivity")
        m = re.search(r"TotalTime: (\d+)", out)
        if m:
            times.append(int(m.group(1)))
            print(f"run {i + 1}: {m.group(1)} ms")
        time.sleep(3)
    if times:
        print(f"cold start median {statistics.median(times)} ms, worst {max(times)} ms")


def cmd_cycles(phone: Phone, args) -> None:
    print(f"Restarting {phone.package} {args.runs} times: the headset's control link drops each time.")
    phone.sh("logcat", "-c")
    for i in range(args.runs):
        phone.sh("shell", "am", "force-stop", phone.package)
        time.sleep(4)
        phone.sh("shell", "am", "start", "-n", f"{phone.package}/dev.earshot.MainActivity")
        time.sleep(args.wait)
        print(f"cycle {i + 1}/{args.runs} done")
    cmd_connect(phone, args)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("what", choices=["connect", "battery", "alive", "startup", "cycles"])
    ap.add_argument("--serial")
    ap.add_argument("--package", default="dev.earshot")
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--wait", type=int, default=12, help="seconds per cycle to wait for the connect")
    args = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    phone = Phone(args.serial, args.package)
    {"connect": cmd_connect, "battery": cmd_battery, "alive": cmd_alive, "startup": cmd_startup, "cycles": cmd_cycles}[args.what](phone, args)


if __name__ == "__main__":
    main()
