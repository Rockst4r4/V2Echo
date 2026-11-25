"""Native macOS system integration & Location/Privacy Guard for Echolicense VPN."""

from __future__ import annotations

import json
import os
import plistlib
import re
import shlex
import subprocess
import sys
from pathlib import Path

IS_MACOS = sys.platform == "darwin"

APP_BUNDLE_ID = "com.echolicense.vpn"
LAUNCH_AGENT_PATH = Path.home() / "Library" / "LaunchAgents" / f"{APP_BUNDLE_ID}.plist"
SUPPORT_DIR = Path.home() / "Library" / "Application Support" / "EcholicenseVPN"
TZ_BACKUP_FILE = SUPPORT_DIR / "tz_backup.json"
PF_CONF_PATH = Path("/tmp/echolicense_killswitch.conf")
PF_ANCHOR_NAME = "com.echolicense.killswitch"

# نگاشت هوشمند نام سرور به منطقه زمانی استاندارد برای Location & Privacy Guard
SERVER_TIMEZONE_MAP = [
    (r"🇺🇸|united states|\busa?\b|america|آمریکا|امریکا|aws|amazon|ashburn|new york|ai[\s\-_]*pro", "America/New_York"),
    (r"🇩🇪|germany|frankfurt|آلمان|\bde\b", "Europe/Berlin"),
    (r"🇳🇱|netherlands|amsterdam|هلند|\bnl\b", "Europe/Amsterdam"),
    (r"🇫🇮|finland|helsinki|فنلاند|\bfi\b", "Europe/Helsinki"),
    (r"🇬🇧|united kingdom|london|انگلیس|\buk\b|\bgb\b", "Europe/London"),
    (r"🇫🇷|france|paris|فرانسه|\bfr\b", "Europe/Paris"),
    (r"🇹🇷|turkey|istanbul|ترکیه|\btr\b", "Europe/Istanbul"),
    (r"🇦🇪|uae|dubai|امارات|دبی|\bae\b", "Asia/Dubai"),
    (r"🇨🇦|canada|toronto|کانادا|\bca\b", "America/Toronto"),
    (r"🇸🇬|singapore|سنگاپور|\bsg\b", "Asia/Singapore"),
]


def _escape_applescript(text: str) -> str:
    return text.replace("\\", "\\\\").replace('"', '\\"')


def run_admin_shell(shell_cmd: str, timeout: int = 30) -> tuple[bool, str]:
    """Execute a shell command with native macOS administrator privilege dialog."""
    if not IS_MACOS:
        return False, "Not running on macOS"
    script = f'do shell script "{_escape_applescript(shell_cmd)}" with administrator privileges'
    try:
        res = subprocess.run(
            ["osascript", "-e", script],
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        if res.returncode == 0:
            return True, (res.stdout or "").strip()
        return False, (res.stderr or res.stdout or "Authorization cancelled").strip()
    except Exception as exc:
        return False, str(exc)


# =====================================================================
# 1. System Proxy (بدون نیاز به پسورد ادمین از طریق networksetup)
# =====================================================================
def get_active_network_services() -> list[str]:
    if not IS_MACOS:
        return []
    try:
        out = subprocess.check_output(["networksetup", "-listallnetworkservices"], text=True, timeout=5)
        services = []
        for line in out.splitlines()[1:]:
            line = line.strip()
            if not line or line.startswith("*"):
                continue
            services.append(line)
        return services
    except Exception:
        return ["Wi-Fi"]


def set_system_proxy(host: str, port: int) -> bool:
    if not IS_MACOS:
        return False
    ok_any = False
    p_str = str(int(port))
    for svc in get_active_network_services():
        try:
            subprocess.run(["networksetup", "-setwebproxy", svc, host, p_str], check=False, capture_output=True, timeout=5)
            subprocess.run(["networksetup", "-setsecurewebproxy", svc, host, p_str], check=False, capture_output=True, timeout=5)
            subprocess.run(["networksetup", "-setsocksfirewallproxy", svc, host, p_str], check=False, capture_output=True, timeout=5)
            subprocess.run(["networksetup", "-setwebproxystate", svc, "on"], check=False, capture_output=True, timeout=5)
            subprocess.run(["networksetup", "-setsecurewebproxystate", svc, "on"], check=False, capture_output=True, timeout=5)
            subprocess.run(["networksetup", "-setsocksfirewallproxystate", svc, "on"], check=False, capture_output=True, timeout=5)
            ok_any = True
        except Exception:
            continue
    return ok_any


def clear_system_proxy() -> bool:
    if not IS_MACOS:
        return False
    ok_any = False
    for svc in get_active_network_services():
        try:
            subprocess.run(["networksetup", "-setwebproxystate", svc, "off"], check=False, capture_output=True, timeout=5)
            subprocess.run(["networksetup", "-setsecurewebproxystate", svc, "off"], check=False, capture_output=True, timeout=5)
            subprocess.run(["networksetup", "-setsocksfirewallproxystate", svc, "off"], check=False, capture_output=True, timeout=5)
            ok_any = True
        except Exception:
            continue
    return ok_any


# =====================================================================
# 2. TUN Mode Execution (اجرای sing-box با دسترسی utun روی مک)
# =====================================================================
def start_singbox_tun_macos(singbox_bin: str, cfg_path: str, log_path: str, pid_path: str) -> tuple[bool, str]:
    """Start sing-box in background with root privileges for utun interface creation."""
    if not IS_MACOS:
        return False, "Not macOS"
    for fp in (log_path, pid_path):
        try:
            p_fp = Path(fp)
            p_fp.parent.mkdir(parents=True, exist_ok=True)
            p_fp.unlink(missing_ok=True)
            p_fp.write_text("", encoding="utf-8")
        except Exception:
            pass
    work_dir = str(Path(singbox_bin).resolve().parent)
    cmd = (
        f"cd {shlex.quote(work_dir)} && "
        f"(trap '' HUP; {shlex.quote(singbox_bin)} run -c {shlex.quote(cfg_path)} "
        f"</dev/null >{shlex.quote(log_path)} 2>&1 & "
        f"echo $! >{shlex.quote(pid_path)}; "
        f"chmod 666 {shlex.quote(log_path)} {shlex.quote(pid_path)} 2>/dev/null || true)"
    )
    return run_admin_shell(cmd)


def stop_singbox_tun_macos(pid_path: str) -> bool:
    if not IS_MACOS:
        return False
    p = Path(pid_path)
    pid = ""
    if p.exists():
        try:
            pid = p.read_text(encoding="utf-8").strip()
        except Exception:
            pid = ""
    if pid.isdigit():
        ok, _ = run_admin_shell(f"kill -TERM {pid} 2>/dev/null || true")
    else:
        ok, _ = run_admin_shell("pkill -TERM -f 'sing-box run' 2>/dev/null || true")
    try:
        p.unlink(missing_ok=True)
    except Exception:
        pass
    return ok


# =====================================================================
# 3. Kill Switch (فایروال بومی مک pfctl)
# =====================================================================
def enable_killswitch(server_ip: str = "") -> tuple[bool, str]:
    if not IS_MACOS:
        return False, "Not macOS"
    rules = [
        "set skip on lo0",
        "pass quick on utun0 all",
        "pass quick on utun1 all",
        "pass quick on utun2 all",
        "pass quick on utun3 all",
        "pass quick on utun4 all",
        "pass quick on utun5 all",
        "pass quick to 127.0.0.0/8",
        "pass quick to 10.0.0.0/8",
        "pass quick to 172.16.0.0/12",
        "pass quick to 192.168.0.0/16",
    ]
    clean_ip = (server_ip or "").strip()
    if clean_ip and re.match(r"^[0-9a-fA-F\.:]+$", clean_ip):
        rules.append(f"pass out quick to {clean_ip}")
    rules.append("block drop out quick all")
    PF_CONF_PATH.write_text("\n".join(rules) + "\n", encoding="utf-8")
    cmd = f"pfctl -E 2>/dev/null || true; pfctl -a {PF_ANCHOR_NAME} -f {shlex.quote(str(PF_CONF_PATH))}"
    return run_admin_shell(cmd)


def disable_killswitch() -> tuple[bool, str]:
    if not IS_MACOS:
        return False, "Not macOS"
    ok, msg = run_admin_shell(f"pfctl -a {PF_ANCHOR_NAME} -F all 2>/dev/null || true")
    try:
        PF_CONF_PATH.unlink(missing_ok=True)
    except Exception:
        pass
    return ok, msg


# =====================================================================
# 4. Auto-Start (LaunchAgent) & Notifications & Running Apps
# =====================================================================
def set_autostart(enabled: bool, exec_path: str | None = None) -> bool:
    if not IS_MACOS:
        return False
    try:
        if not enabled:
            LAUNCH_AGENT_PATH.unlink(missing_ok=True)
            return True
        LAUNCH_AGENT_PATH.parent.mkdir(parents=True, exist_ok=True)
        target = exec_path or sys.executable
        plist_data = {
            "Label": APP_BUNDLE_ID,
            "ProgramArguments": [target],
            "RunAtLoad": True,
            "KeepAlive": False,
        }
        with LAUNCH_AGENT_PATH.open("wb") as fh:
            plistlib.dump(plist_data, fh)
        return True
    except Exception:
        return False


def is_autostart_enabled() -> bool:
    return IS_MACOS and LAUNCH_AGENT_PATH.exists()


def send_notification(title: str, message: str) -> None:
    if not IS_MACOS:
        return
    try:
        script = f'display notification "{_escape_applescript(message)}" with title "{_escape_applescript(title)}"'
        subprocess.run(["osascript", "-e", script], check=False, capture_output=True, timeout=5)
    except Exception:
        pass


def list_installed_and_running_apps() -> list[str]:
    if not IS_MACOS:
        return []
    names: set[str] = set()
    for app_dir in (Path("/Applications"), Path.home() / "Applications"):
        if app_dir.exists():
            for item in app_dir.glob("*.app"):
                names.add(item.stem)
    try:
        out = subprocess.check_output(["ps", "-eo", "comm="], text=True, timeout=5)
        for line in out.splitlines():
            base = Path(line.strip()).name
            if base and not base.startswith("-") and len(base) > 1:
                names.add(base)
    except Exception:
        pass
    return sorted(names, key=lambda x: x.lower())


# =====================================================================
# 5. Location & Privacy Guard (جایگزین اختصاصی GPS Override در مک)
# =====================================================================
def get_system_timezone() -> str:
    if not IS_MACOS:
        return ""
    try:
        link = os.path.realpath("/etc/localtime")
        marker = "zoneinfo/"
        if marker in link:
            return link.split(marker, 1)[1]
    except Exception:
        pass
    try:
        out = subprocess.check_output(["systemsetup", "-gettimezone"], text=True, timeout=5)
        if ":" in out:
            return out.split(":", 1)[1].strip()
    except Exception:
        pass
    return "Unknown"


def expected_timezone_for_server(server_name: str, smart_ai_enabled: bool = False) -> str:
    low = (server_name or "").lower()
    for pattern, tz in SERVER_TIMEZONE_MAP:
        if re.search(pattern, low, re.I):
            return tz
    if smart_ai_enabled:
        return "America/New_York"
    return "America/New_York"


def _load_tz_backup() -> str | None:
    try:
        if TZ_BACKUP_FILE.exists():
            data = json.loads(TZ_BACKUP_FILE.read_text(encoding="utf-8"))
            return data.get("original_timezone")
    except Exception:
        pass
    return None


def _save_tz_backup(tz: str) -> None:
    SUPPORT_DIR.mkdir(parents=True, exist_ok=True)
    TZ_BACKUP_FILE.write_text(json.dumps({"original_timezone": tz}), encoding="utf-8")


def sync_timezone_for_server(server_name: str, smart_ai_enabled: bool = False) -> dict:
    if not IS_MACOS:
        return {"ok": False, "error": "macOS only"}
    current_tz = get_system_timezone()
    target_tz = expected_timezone_for_server(server_name, smart_ai_enabled)
    if not re.match(r"^[A-Za-z0-9_\-/]+$", target_tz):
        return {"ok": False, "error": "Invalid timezone"}
    if current_tz == target_tz:
        return {"ok": True, "timezone": target_tz, "changed": False}
    if not _load_tz_backup() and current_tz and current_tz != "Unknown":
        _save_tz_backup(current_tz)
    ok, msg = run_admin_shell(f"systemsetup -settimezone {shlex.quote(target_tz)}")
    return {"ok": ok, "timezone": target_tz if ok else current_tz, "error": "" if ok else msg}


def restore_original_timezone() -> dict:
    if not IS_MACOS:
        return {"ok": False, "error": "macOS only"}
    orig = _load_tz_backup()
    if not orig:
        return {"ok": True, "restored": False, "timezone": get_system_timezone()}
    ok, msg = run_admin_shell(f"systemsetup -settimezone {shlex.quote(orig)}")
    if ok:
        try:
            TZ_BACKUP_FILE.unlink(missing_ok=True)
        except Exception:
            pass
    return {"ok": ok, "restored": ok, "timezone": orig if ok else get_system_timezone(), "error": "" if ok else msg}


def open_macos_location_settings() -> bool:
    if not IS_MACOS:
        return False
    try:
        subprocess.run(
            ["open", "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices"],
            check=False,
            timeout=5,
        )
        return True
    except Exception:
        return False


def get_privacy_guard_status(server_name: str, is_connected: bool, smart_ai_enabled: bool = False) -> dict:
    sys_tz = get_system_timezone() if IS_MACOS else ""
    rec_tz = expected_timezone_for_server(server_name, smart_ai_enabled)
    orig_tz = _load_tz_backup()
    is_iran_tz = "tehran" in sys_tz.lower()
    mismatch = bool(IS_MACOS and is_connected and (is_iran_tz or (sys_tz and sys_tz != rec_tz)))
    return {
        "supported": IS_MACOS,
        "systemTimezone": sys_tz,
        "recommendedTimezone": rec_tz,
        "timezoneMismatch": mismatch,
        "timezoneOverridden": bool(orig_tz),
        "originalTimezone": orig_tz or "",
        "quicGuard": True,
        "dnsLeakGuard": True,
    }


def register_url_handler(callback) -> bool:
    """Register native macOS kAEGetURL handler for echolicense:// deep links."""
    if not IS_MACOS:
        return False
    try:
        import struct
        import objc
        from Foundation import NSAppleEventManager, NSObject

        class _EchoUrlHandler(NSObject):
            def handleGetURLEvent_withReplyEvent_(self, event, _reply):
                try:
                    key_direct = struct.unpack(">I", b"----")[0]
                    desc = event.paramDescriptorForKeyword_(key_direct)
                    if desc is not None:
                        uri = str(desc.stringValue() or "")
                        if uri.lower().startswith("echolicense://"):
                            callback(uri)
                except Exception:
                    pass

        handler = _EchoUrlHandler.alloc().init()
        # نگه‌داشتن رفرنس در ماژول تا توسط GC پاک نشود
        globals()["_MAC_URL_HANDLER_REF"] = handler
        mgr = NSAppleEventManager.sharedAppleEventManager()
        event_class = struct.unpack(">I", b"GURL")[0]
        event_id = struct.unpack(">I", b"GURL")[0]
        mgr.setEventHandler_andSelector_forEventClass_andEventID_(
            handler,
            objc.selector(handler.handleGetURLEvent_withReplyEvent_, signature=b"v@:@@"),
            event_class,
            event_id,
        )
        return True
    except Exception:
        return False


def get_macos_hardware_uuid() -> str:
    if not IS_MACOS:
        return ""
    try:
        out = subprocess.check_output(
            ["ioreg", "-rd1", "-c", "IOPlatformExpertDevice"],
            text=True,
            timeout=5,
        )
        m = re.search(r'"IOPlatformUUID"\s*=\s*"([^"]+)"', out)
        if m:
            return m.group(1).strip()
    except Exception:
        pass
    return ""
