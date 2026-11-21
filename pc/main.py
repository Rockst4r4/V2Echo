import atexit
import base64
import hashlib
import json
import os
import platform
import re
import socket
import subprocess
import sys
import threading
import time
import uuid
import webbrowser
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlparse, parse_qs, unquote

import psutil
import requests
import webview

from singbox_config import build_singbox_config

try:
    import winreg
except ImportError:
    winreg = None

APP_NAME = "EcholicenseVPN"
APP_VERSION = "1.0.0"
ANNOUNCEMENT_URL = "https://raw.githubusercontent.com/Rockst4r4/V2Echo/main/announcements.json"
SOCKS_PORT = 10808
STARTUP_REG_PATH = r"Software\Microsoft\Windows\CurrentVersion\Run"
PROXY_REG_PATH = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"
KILLSWITCH_PREFIX = "EcholicenseKillSwitch"
WG_TUNNEL_NAME = "EcholicenseWG"
ANSI_RE = re.compile(r"\x1B\[[0-?]*[ -/]*[@-~]")


def strip_ansi(text: str) -> str:
    return ANSI_RE.sub("", text or "").strip()


def find_free_port(preferred: int = 10808) -> int:
    for port in [preferred] + list(range(10809, 10890)):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    return preferred


def get_app_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def get_resource_dir() -> Path:
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS)
    return Path(__file__).resolve().parent


def get_data_dir() -> Path:
    base = Path(os.environ.get("APPDATA", Path.home())) / APP_NAME
    base.mkdir(parents=True, exist_ok=True)
    return base


def get_hwid() -> str:
    raw = f"{platform.node()}-{uuid.getnode()}-{platform.machine()}"
    if winreg:
        try:
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Cryptography") as key:
                guid, _ = winreg.QueryValueEx(key, "MachineGuid")
                if guid:
                    raw = f"echo_pc_{guid}"
        except Exception:
            pass
    return hashlib.sha256(raw.encode()).hexdigest()[:32]


def get_device_headers() -> dict:
    return {
        "User-Agent": "v2rayNG/1.9.15",
        "Accept": "*/*",
        "x-hwid": get_hwid(),
        "x-device-os": "Windows",
        "x-ver-os": platform.release(),
        "x-device-model": f"PC {platform.machine()}",
    }


def is_ai_pro(name: str) -> bool:
    low = name.lower()
    if any(k in low for k in ("ai pro", "aipro", "ai-pro", "ai_pro")):
        return True
    return bool(re.search(r"(^|[\s\-_\[\]()|])ai([\s\-_\[\]()|]|$)", low))


def is_gaming(name: str, proto: str) -> bool:
    low = name.lower()
    has_word = any(k in low for k in ("gaming", "game", "گیم"))
    return proto.upper() == "WIREGUARD" and has_word


def parse_endpoint(endpoint: str) -> tuple[str, int]:
    ep = endpoint.strip()
    if ep.startswith("[") and "]:" in ep:
        h, p = ep[1:].split("]:", 1)
        return h, int(p) if p.isdigit() else 51820
    if ":" in ep:
        h, p = ep.rsplit(":", 1)
        return h.strip(), int(p.strip()) if p.strip().isdigit() else 51820
    return ep or "wireguard", 51820


def parse_wireguard_uri(line: str) -> dict | None:
    try:
        base, frag = (line.split("#", 1) + [""])[:2]
        rest = base.split("://", 1)[1]
        if "@" not in rest:
            return None
        priv_raw, after_at = rest.split("@", 1)
        priv = unquote(priv_raw).strip()
        host_part, query_raw = (after_at.split("?", 1) + [""])[:2]
        host, port = parse_endpoint(host_part.rstrip("/"))
        params = {k.lower(): unquote(v[0]) for k, v in parse_qs(query_raw).items() if v}
        pub = params.get("publickey", "").strip()
        if not pub:
            return None
        addr = params.get("address", "10.0.0.2/32")
        psk = params.get("presharedkey", "").strip()
        reserved = params.get("reserved", "").strip()
        ep_fmt = f"[{host}]:{port}" if ":" in host else f"{host}:{port}"
        lines = [
            "[Interface]",
            f"PrivateKey = {priv}",
            f"Address = {addr}",
            "MTU = 1280",
        ]
        if reserved:
            lines.append(f"Reserved = {reserved}")
        lines += [
            "",
            "[Peer]",
            f"PublicKey = {pub}",
            f"Endpoint = {ep_fmt}",
            "AllowedIPs = 0.0.0.0/0, ::/0",
            "PersistentKeepalive = 25",
        ]
        if psk:
            lines.append(f"PresharedKey = {psk}")
        name = unquote(frag).strip() or f"WireGuard ({host})"
        return {
            "id": str(uuid.uuid4()),
            "name": name,
            "protocol": "WIREGUARD",
            "rawConfig": "\n".join(lines),
            "host": host,
            "port": port,
            "pingMs": -1,
            "isAiPro": is_ai_pro(name),
            "isGaming": is_gaming(name, "WIREGUARD"),
        }
    except Exception:
        return None


def parse_servers(raw_text: str) -> list[dict]:
    text = raw_text.strip()
    if "[interface]" in text.lower() and "[peer]" in text.lower():
        ep_val = ""
        for ln in text.splitlines():
            clean = ln.split("#", 1)[0].split(";", 1)[0].strip()
            if clean.lower().startswith("endpoint") and "=" in clean:
                ep_val = clean.split("=", 1)[1].strip()
                break
        host, port = parse_endpoint(ep_val)
        name = f"Gaming WireGuard ({host})"
        return [{
            "id": str(uuid.uuid4()),
            "name": name,
            "protocol": "WIREGUARD",
            "rawConfig": text,
            "host": host,
            "port": port,
            "pingMs": -1,
            "isAiPro": False,
            "isGaming": True,
        }]

    if "://" not in text:
        try:
            pad = "=" * ((4 - len(text.replace("\n", "")) % 4) % 4)
            text = base64.b64decode(text.replace("\n", "") + pad).decode("utf-8", errors="ignore")
        except Exception:
            pass

    out = []
    for line in [l.strip() for l in text.splitlines() if l.strip()]:
        low = line.lower()
        if low.startswith("wireguard://"):
            item = parse_wireguard_uri(line)
            if item:
                out.append(item)
        elif low.startswith(("vless://", "trojan://")):
            proto = "VLESS" if low.startswith("vless://") else "TROJAN"
            try:
                base, frag = (line.split("#", 1) + [""])[:2]
                u = urlparse(base.replace(" ", "%20"))
                if not u.hostname:
                    continue
                name = unquote(frag).strip() or f"{proto} Server"
                out.append({
                    "id": str(uuid.uuid4()),
                    "name": name,
                    "protocol": proto,
                    "rawConfig": line,
                    "host": u.hostname,
                    "port": u.port or 443,
                    "pingMs": -1,
                    "isAiPro": is_ai_pro(name),
                    "isGaming": is_gaming(name, proto),
                })
            except Exception:
                continue
        elif low.startswith("vmess://"):
            try:
                b64 = line.split("://", 1)[1].strip()
                pad = "=" * ((4 - len(b64) % 4) % 4)
                obj = json.loads(base64.b64decode(b64 + pad).decode("utf-8"))
                name = str(obj.get("ps") or "VMess Server")
                out.append({
                    "id": str(uuid.uuid4()),
                    "name": name,
                    "protocol": "VMESS",
                    "rawConfig": line,
                    "host": str(obj.get("add", "")),
                    "port": int(obj.get("port", 443)),
                    "pingMs": -1,
                    "isAiPro": is_ai_pro(name),
                    "isGaming": is_gaming(name, "VMESS"),
                })
            except Exception:
                continue
    return out


def parse_userinfo(header: str) -> dict:
    info = {"upload": 0, "download": 0, "total": 0, "expire": 0, "hasInfo": False}
    for part in (header or "").split(";"):
        if "=" in part:
            k, v = [x.strip() for x in part.split("=", 1)]
            try:
                num = int(float(v))
            except ValueError:
                num = 0
            kl = k.lower()
            if kl in ("upload", "download", "total", "expire"):
                info[kl] = num
                info["hasInfo"] = True
    return info


class EcholicenseApi:
    def __init__(self):
        self._data_dir = get_data_dir()
        self._state_file = self._data_dir / "state.json"
        self._log_file = self._data_dir / "core.log"
        self._proc: subprocess.Popen | None = None
        self._native_wg_active = False
        self._log_fh = None
        self._active_port = SOCKS_PORT
        self._connected_at = 0.0
        self._last_net = psutil.net_io_counters()
        self._last_net_ts = time.time()
        self._window = None

        self._state = self._load_saved_state()
        self._state["connectionState"] = "DISCONNECTED"
        self._state["statusMessage"] = "Ready to connect"
        self._state["version"] = APP_VERSION
        self._state["hwid"] = get_hwid()

        atexit.register(self._cleanup_on_exit)
        threading.Thread(target=self._background_loops, daemon=True).start()

    def _default_state(self) -> dict:
        return {
            "language": "en",
            "mode": "tun",
            "connectionState": "DISCONNECTED",
            "statusMessage": "Ready to connect",
            "subscriptions": [],
            "activeSubId": None,
            "servers": [],
            "activeServerId": None,
            "subInfo": {"upload": 0, "download": 0, "total": 0, "expire": 0, "hasInfo": False},
            "aiProOnly": False,
            "gamingOnly": False,
            "killSwitch": False,
            "startupApp": False,
            "autoConnect": False,
            "iranSplit": True,
            "adBlocker": True,
            "autoMtu": True,
            "mtu": 1360,
            "manualDns": "",
            "activeDns": "Auto Failover",
            "mux": {"enabled": False, "concurrency": 8, "xudp": 16},
            "splitMode": "bypass",
            "splitApps": [],
            "announcement": None,
            "dismissedAnnouncementId": 0,
            "botUsername": "echopress_bot",
        }

    def _load_saved_state(self) -> dict:
        st = self._default_state()
        if self._state_file.exists():
            try:
                saved = json.loads(self._state_file.read_text(encoding="utf-8"))
                st.update(saved)
            except Exception:
                pass
        return st

    def _save_state(self):
        persist_keys = [
            "language", "mode", "subscriptions", "activeSubId", "servers", "activeServerId",
            "subInfo", "aiProOnly", "gamingOnly", "killSwitch", "startupApp", "autoConnect",
            "iranSplit", "adBlocker", "autoMtu", "mtu", "manualDns", "mux",
            "splitMode", "splitApps", "dismissedAnnouncementId", "botUsername",
        ]
        payload = {k: self._state.get(k) for k in persist_keys}
        try:
            self._state_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        except Exception:
            pass

    def _run_hidden(self, args: list[str]) -> int:
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
        try:
            r = subprocess.run(args, capture_output=True, text=True, creationflags=flags, timeout=5)
            return r.returncode
        except Exception:
            return 1

    def _set_windows_proxy(self, enabled: bool):
        if not winreg:
            return
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, PROXY_REG_PATH, 0, winreg.KEY_SET_VALUE) as key:
                winreg.SetValueEx(key, "ProxyEnable", 0, winreg.REG_DWORD, 1 if enabled else 0)
                if enabled:
                    winreg.SetValueEx(key, "ProxyServer", 0, winreg.REG_SZ, f"127.0.0.1:{self._active_port}")
                    winreg.SetValueEx(key, "ProxyOverride", 0, winreg.REG_SZ, "localhost;127.*;10.*;192.168.*;*.ir;<local>")
        except Exception:
            pass

    def _apply_startup_registry(self, enabled: bool):
        if not winreg:
            return
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, STARTUP_REG_PATH, 0, winreg.KEY_SET_VALUE) as key:
                if enabled:
                    exe = f'"{sys.executable}"' if getattr(sys, "frozen", False) else f'"{sys.executable}" "{Path(__file__).resolve()}"'
                    winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, exe)
                else:
                    try:
                        winreg.DeleteValue(key, APP_NAME)
                    except FileNotFoundError:
                        pass
        except Exception:
            pass

    def _clear_killswitch_rules(self):
        if os.name != "nt":
            return
        for suffix in ("AllowLoopback", "AllowServer", "AllowLan", "AllowCore", "BlockLeak"):
            self._run_hidden(["netsh", "advfirewall", "firewall", "delete", "rule", f"name={KILLSWITCH_PREFIX}_{suffix}"])

    def _apply_killswitch_rules(self, server_host: str):
        if os.name != "nt":
            return
        self._clear_killswitch_rules()
        try:
            server_ip = socket.gethostbyname(server_host)
        except Exception:
            server_ip = ""
        self._run_hidden(["netsh", "advfirewall", "firewall", "add", "rule", f"name={KILLSWITCH_PREFIX}_AllowLoopback", "dir=out", "action=allow", "remoteip=127.0.0.0/8"])
        self._run_hidden(["netsh", "advfirewall", "firewall", "add", "rule", f"name={KILLSWITCH_PREFIX}_AllowLan", "dir=out", "action=allow", "remoteip=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"])
        if server_ip:
            self._run_hidden(["netsh", "advfirewall", "firewall", "add", "rule", f"name={KILLSWITCH_PREFIX}_AllowServer", "dir=out", "action=allow", f"remoteip={server_ip}"])
        singbox_exe = str(get_app_dir() / "sing-box.exe")
        if os.path.exists(singbox_exe):
            self._run_hidden(["netsh", "advfirewall", "firewall", "add", "rule", f"name={KILLSWITCH_PREFIX}_AllowCore", "dir=out", "action=allow", f"program={singbox_exe}"])


    def _build_native_wg_conf(self, raw_conf: str, dns_ip: str, mtu: int) -> str | None:
        section = ""
        priv_key, pub_key, psk, endpoint, addrs = "", "", "", "", []
        for raw_ln in raw_conf.splitlines():
            ln = raw_ln.split("#", 1)[0].split(";", 1)[0].strip()
            if not ln:
                continue
            if ln.startswith("[") and ln.endswith("]"):
                section = ln.lower()
                continue
            if "=" not in ln:
                continue
            k, v = [x.strip() for x in ln.split("=", 1)]
            kl = k.lower()
            if kl == "reserved" and v:
                return None  # کانفیگ‌های WARP دارای Reserved روی sing-box اجرا می‌شوند
            if section == "[interface]":
                if kl == "privatekey":
                    priv_key = v
                elif kl == "address":
                    addrs = [a.strip() for a in v.split(",") if a.strip()]
            elif section == "[peer]":
                if kl == "publickey":
                    pub_key = v
                elif kl == "presharedkey":
                    psk = v
                elif kl == "endpoint":
                    endpoint = v
        if not priv_key or not pub_key or not endpoint:
            return None
        dns_clean = dns_ip if re.match(r"^\d{1,3}(\.\d{1,3}){3}$", dns_ip) else "1.1.1.1"
        lines = [
            "[Interface]",
            f"PrivateKey = {priv_key}",
            f"Address = {', '.join(addrs) if addrs else '10.0.0.2/32'}",
            f"DNS = {dns_clean}, 1.0.0.1",
            f"MTU = {max(1200, min(1500, int(mtu)))}",
            "",
            "[Peer]",
            f"PublicKey = {pub_key}",
            f"Endpoint = {endpoint}",
            "AllowedIPs = 0.0.0.0/0",
            "PersistentKeepalive = 25",
        ]
        if psk:
            lines.append(f"PresharedKey = {psk}")
        return "\n".join(lines) + "\n"

    def _start_native_wg(self, srv: dict, dns_addr: str, mtu: int) -> bool:
        wg_exe = get_app_dir() / "wireguard.exe"
        if os.name != "nt" or not wg_exe.exists():
            return False
        if self._state.get("splitApps"):
            return False
        dns_ip = "94.140.14.14" if self._state.get("adBlocker") and not (self._state.get("manualDns") or "").strip() else dns_addr
        conf_text = self._build_native_wg_conf(srv["rawConfig"], dns_ip, mtu)
        if not conf_text:
            return False
        conf_dir = Path(os.environ.get("ProgramData", str(self._data_dir))) / APP_NAME
        conf_dir.mkdir(parents=True, exist_ok=True)
        conf_path = conf_dir / f"{WG_TUNNEL_NAME}.conf"
        conf_path.write_text(conf_text, encoding="utf-8")

        self._run_hidden([str(wg_exe), "/uninstalltunnelservice", WG_TUNNEL_NAME])
        time.sleep(0.4)
        rc = self._run_hidden([str(wg_exe), "/installtunnelservice", str(conf_path)])
        if rc != 0:
            self._log_file.write_text(f"Native WireGuard install failed (rc={rc}), falling back to sing-box\n", encoding="utf-8")
            return False
        time.sleep(1.0)
        self._native_wg_active = True
        self._log_file.write_text(f"Native WireGuard-NT kernel tunnel started ({srv['name']}, MTU={mtu})\n", encoding="utf-8")
        return True

    def _stop_native_wg(self):
        if os.name != "nt":
            return
        wg_exe = get_app_dir() / "wireguard.exe"
        if wg_exe.exists() and self._native_wg_active:
            self._run_hidden([str(wg_exe), "/uninstalltunnelservice", WG_TUNNEL_NAME])
        self._native_wg_active = False

    def _detect_auto_mtu(self, proto: str) -> int:
        overhead = 80 if proto.upper() == "WIREGUARD" else 60
        candidates = (1452, 1392, 1322)
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

        def _probe(payload: int) -> int:
            try:
                r = subprocess.run(
                    ["ping", "-n", "1", "-f", "-l", str(payload), "-w", "450", "1.1.1.1"],
                    capture_output=True,
                    creationflags=flags,
                    timeout=0.9,
                )
                return payload if r.returncode == 0 else 0
            except Exception:
                return 0

        try:
            with ThreadPoolExecutor(max_workers=3) as ex:
                ok = [v for v in ex.map(_probe, candidates) if v > 0]
            if ok:
                return max(1280, min(1440, max(ok) + 28 - overhead))
        except Exception:
            pass
        return 1280 if proto.upper() == "WIREGUARD" else 1360

    def _resolve_dns(self) -> tuple[str, str]:
        manual = (self._state.get("manualDns") or "").strip()
        if manual:
            return ("Manual DNS", manual)
        if self._state.get("adBlocker"):
            return ("AdGuard AdBlock", "https://dns.adguard-dns.com/dns-query")
        providers = [
            ("Cloudflare", "1.1.1.1", "https://1.1.1.1/dns-query"),
            ("Quad9", "9.9.9.9", "https://dns.quad9.net/dns-query"),
            ("Google", "8.8.8.8", "https://dns.google/dns-query"),
        ]

        def _check(item):
            name, ip, doh = item
            try:
                with socket.create_connection((ip, 443), timeout=0.7):
                    return (name, doh)
            except Exception:
                return None

        try:
            with ThreadPoolExecutor(max_workers=3) as ex:
                for res in ex.map(_check, providers):
                    if res:
                        return res
        except Exception:
            pass
        return ("Cloudflare", "https://1.1.1.1/dns-query")

    def _background_loops(self):
        time.sleep(1.5)
        self._fetch_announcement()
        if self._state.get("autoConnect") and self._state.get("activeServerId"):
            self.toggle_connect()
        last_sync = time.time()
        while True:
            time.sleep(60)
            if time.time() - last_sync >= 12 * 3600:
                last_sync = time.time()
                active_sub = self._get_active_sub()
                if active_sub:
                    self._sync_sub_url(active_sub["url"])
                self._fetch_announcement()

    def _fetch_announcement(self):
        try:
            r = requests.get(f"{ANNOUNCEMENT_URL}?t={int(time.time() // 60)}", timeout=8)
            if r.status_code == 200:
                data = r.json()
                self._state["botUsername"] = data.get("bot_username") or "echopress_bot"
                if data.get("active") and data.get("id") != self._state.get("dismissedAnnouncementId"):
                    self._state["announcement"] = data
                else:
                    self._state["announcement"] = None
        except Exception:
            pass

    def _get_active_sub(self):
        sid = self._state.get("activeSubId")
        return next((s for s in self._state["subscriptions"] if s["id"] == sid), None)

    def _get_active_server(self):
        sid = self._state.get("activeServerId")
        return next((s for s in self._state["servers"] if s["id"] == sid), None)

    def _sync_sub_url(self, url: str) -> dict:
        r = requests.get(url.strip(), headers=get_device_headers(), timeout=15, allow_redirects=True)
        r.raise_for_status()
        hdr = r.headers.get("Subscription-Userinfo") or r.headers.get("subscription-userinfo") or ""
        info = parse_userinfo(hdr)
        servers = parse_servers(r.text)
        self._state["subInfo"] = info
        self._state["servers"] = servers
        if servers:
            self._state["activeServerId"] = servers[0]["id"]
        self._state["statusMessage"] = f"{len(servers)} servers ready"
        self._save_state()
        return self.get_state()

    def _cleanup_on_exit(self):
        self.disconnect()
        self._clear_killswitch_rules()

    def get_state(self) -> dict:
        now = time.time()
        net = psutil.net_io_counters()
        dt = max(0.2, now - self._last_net_ts)
        down_kbps = int(max(0, net.bytes_recv - self._last_net.bytes_recv) / 1024 / dt)
        up_kbps = int(max(0, net.bytes_sent - self._last_net.bytes_sent) / 1024 / dt)
        self._last_net = net
        self._last_net_ts = now

        if self._proc and self._proc.poll() is not None:
            self._proc = None
            self._state["connectionState"] = "DISCONNECTED"
            self._set_windows_proxy(False)

        connected_sec = int(now - self._connected_at) if self._state["connectionState"] == "CONNECTED" else 0
        out = dict(self._state)
        out["downKbps"] = down_kbps if self._state["connectionState"] == "CONNECTED" else 0
        out["upKbps"] = up_kbps if self._state["connectionState"] == "CONNECTED" else 0
        out["connectedSeconds"] = connected_sec
        return out

    def toggle_connect(self) -> dict:
        if self._state["connectionState"] in ("CONNECTED", "CONNECTING"):
            return self.disconnect()
        return self.connect()

    def connect(self) -> dict:
        try:
            return self._connect_impl()
        except Exception as exc:
            self._state["connectionState"] = "ERROR"
            self._state["statusMessage"] = f"Error: {exc}"
            try:
                self._log_file.write_text(f"Connect exception: {exc}\n", encoding="utf-8")
            except Exception:
                pass
            return self.get_state()

    def _connect_impl(self) -> dict:
        srv = self._get_active_server()
        if not srv:
            self._state["statusMessage"] = "Add a subscription or config first"
            return self.get_state()

        singbox_bin = get_app_dir() / "sing-box.exe"
        if not singbox_bin.exists():
            self._state["statusMessage"] = "sing-box.exe not found next to app"
            return self.get_state()

        self.disconnect(keep_killswitch=True)
        self._state["connectionState"] = "CONNECTING"
        self._state["statusMessage"] = "Optimizing DNS & MTU..."

        dns_name, dns_addr = self._resolve_dns()
        mtu = self._detect_auto_mtu(srv["protocol"]) if self._state.get("autoMtu") else int(self._state.get("mtu", 1360))
        self._state["activeDns"] = dns_name
        self._state["mtu"] = mtu

        self._active_port = find_free_port(SOCKS_PORT)
        mode = self._state.get("mode", "tun")
        stacks_to_try = ["system", "mixed", "gvisor"] if mode == "tun" else ["system"]
        cfg_path = self._data_dir / "active_config.json"
        flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0

        if self._state.get("killSwitch"):
            self._apply_killswitch_rules(srv["host"])

        # اجرای WireGuard روی درایور کرنل بومی برای پینگ واقعی ICMP در CMD (بدون <1ms فیک)
        if mode == "tun" and srv.get("protocol", "").upper() == "WIREGUARD":
            if self._start_native_wg(srv, dns_addr, mtu):
                self._set_windows_proxy(False)
                self._connected_at = time.time()
                self._state["connectionState"] = "CONNECTED"
                self._state["statusMessage"] = f"Connected • WireGuard Kernel TUN (Real Ping) • MTU {mtu}"
                self._save_state()
                return self.get_state()

        last_err = "Core failed to start (run as Admin for TUN)"
        started = False

        for stack in stacks_to_try:
            cfg = build_singbox_config(
                profile=srv,
                mode=mode,
                dns_server=dns_addr,
                mtu=mtu,
                iran_split=bool(self._state.get("iranSplit", True)),
                adblock=bool(self._state.get("adBlocker", True)),
                mux_cfg=self._state.get("mux", {}),
                split_mode=self._state.get("splitMode", "bypass"),
                split_apps=self._state.get("splitApps", []),
                socks_port=self._active_port,
                tun_stack=stack,
                app_dir=str(get_app_dir()),
            )
            cfg_path.write_text(json.dumps(cfg, indent=2), encoding="utf-8")

            if self._log_fh:
                try:
                    self._log_fh.close()
                except Exception:
                    pass
                self._log_fh = None

            env = dict(os.environ)
            env["NO_COLOR"] = "1"
            self._log_fh = open(self._log_file, "w", encoding="utf-8", errors="replace")
            self._proc = subprocess.Popen(
                [str(singbox_bin), "run", "-c", str(cfg_path)],
                cwd=str(get_app_dir()),
                stdout=self._log_fh,
                stderr=subprocess.STDOUT,
                creationflags=flags,
                env=env,
            )
            time.sleep(1.1)
            if self._proc.poll() is None:
                started = True
                break

            try:
                self._log_fh.flush()
                self._log_fh.close()
            except Exception:
                pass
            self._log_fh = None
            self._proc = None

            try:
                raw_log = strip_ansi(self._log_file.read_text(encoding="utf-8", errors="ignore"))
                lines = [ln.strip() for ln in raw_log.splitlines() if ln.strip()]
                if lines:
                    last_err = lines[-1]
            except Exception:
                pass

        if not started:
            self._state["connectionState"] = "ERROR"
            self._state["statusMessage"] = last_err
            return self.get_state()

        if mode == "proxy":
            self._set_windows_proxy(True)
        else:
            self._set_windows_proxy(False)

        self._connected_at = time.time()
        self._state["connectionState"] = "CONNECTED"
        mode_lbl = "TUN (Real Ping)" if mode == "tun" else "System Proxy"
        self._state["statusMessage"] = f"Connected • {mode_lbl} • MTU {mtu}"
        self._save_state()
        return self.get_state()

    def disconnect(self, keep_killswitch: bool = False) -> dict:
        self._stop_native_wg()
        if self._proc:
            try:
                self._proc.terminate()
                self._proc.wait(timeout=3)
            except Exception:
                try:
                    self._proc.kill()
                except Exception:
                    pass
            self._proc = None
        if self._log_fh:
            try:
                self._log_fh.close()
            except Exception:
                pass
            self._log_fh = None
        self._set_windows_proxy(False)
        if not keep_killswitch and not self._state.get("killSwitch"):
            self._clear_killswitch_rules()
        self._state["connectionState"] = "DISCONNECTED"
        self._state["statusMessage"] = "Disconnected"
        return self.get_state()

    def add_subscription(self, name: str, raw_input: str) -> dict:
        raw_input = (raw_input or "").strip()
        if not raw_input:
            return self.get_state()
        if raw_input.lower().startswith(("http://", "https://")):
            item = {
                "id": str(uuid.uuid4()),
                "name": (name or "").strip() or f"Sub #{len(self._state['subscriptions']) + 1}",
                "url": raw_input,
            }
            self._state["subscriptions"].append(item)
            self._state["activeSubId"] = item["id"]
            try:
                return self._sync_sub_url(raw_input)
            except Exception as exc:
                self._state["statusMessage"] = f"Error: {exc}"
                return self.get_state()
        else:
            parsed = parse_servers(raw_input)
            self._state["servers"].extend(parsed)
            if parsed:
                self._state["activeServerId"] = parsed[0]["id"]
                self._state["statusMessage"] = f"Added {len(parsed)} config(s)"
            self._save_state()
            return self.get_state()

    def switch_subscription(self, sub_id: str) -> dict:
        target = next((s for s in self._state["subscriptions"] if s["id"] == sub_id), None)
        if not target:
            return self.get_state()
        self.disconnect()
        self._state["activeSubId"] = sub_id
        try:
            return self._sync_sub_url(target["url"])
        except Exception as exc:
            self._state["statusMessage"] = f"Sync error: {exc}"
            return self.get_state()

    def delete_subscription(self, sub_id: str) -> dict:
        self._state["subscriptions"] = [s for s in self._state["subscriptions"] if s["id"] != sub_id]
        if self._state.get("activeSubId") == sub_id:
            self.disconnect()
            next_sub = self._state["subscriptions"][0] if self._state["subscriptions"] else None
            self._state["activeSubId"] = next_sub["id"] if next_sub else None
            if next_sub:
                try:
                    return self._sync_sub_url(next_sub["url"])
                except Exception:
                    pass
            self._state["servers"] = []
            self._state["activeServerId"] = None
            self._state["subInfo"] = {"upload": 0, "download": 0, "total": 0, "expire": 0, "hasInfo": False}
        self._save_state()
        return self.get_state()

    def refresh_subscription(self) -> dict:
        sub = self._get_active_sub()
        if not sub:
            return self.get_state()
        try:
            return self._sync_sub_url(sub["url"])
        except Exception as exc:
            self._state["statusMessage"] = f"Refresh error: {exc}"
            return self.get_state()

    def select_server(self, srv_id: str) -> dict:
        was_conn = self._state["connectionState"] == "CONNECTED"
        self._state["activeServerId"] = srv_id
        self._save_state()
        return self.connect() if was_conn else self.get_state()

    def ping_all(self) -> dict:
        def _ping_one(srv: dict) -> dict:
            t0 = time.time()
            try:
                with socket.create_connection((srv["host"], int(srv["port"])), timeout=1.4):
                    srv["pingMs"] = max(1, int((time.time() - t0) * 1000))
            except Exception:
                srv["pingMs"] = -1
            return srv

        with ThreadPoolExecutor(max_workers=12) as ex:
            results = list(ex.map(_ping_one, self._state["servers"]))
        results.sort(key=lambda x: x["pingMs"] if x["pingMs"] > 0 else 999999)
        self._state["servers"] = results
        fastest = next((x["pingMs"] for x in results if x["pingMs"] > 0), None)
        self._state["statusMessage"] = f"Ping done • Fastest: {fastest}ms" if fastest else "Ping completed"
        self._save_state()
        return self.get_state()

    def set_mode(self, mode: str) -> dict:
        was_conn = self._state["connectionState"] == "CONNECTED"
        self._state["mode"] = "proxy" if mode == "proxy" else "tun"
        self._save_state()
        return self.connect() if was_conn else self.get_state()

    def toggle_ai_pro(self) -> dict:
        next_val = not bool(self._state.get("aiProOnly"))
        if next_val:
            ai_list = [s for s in self._state["servers"] if s.get("isAiPro")]
            if not ai_list:
                self._state["statusMessage"] = "No AI Pro server found"
                return self.get_state()
            self._state["aiProOnly"] = True
            self._state["gamingOnly"] = False
            self._state["activeServerId"] = ai_list[0]["id"]
            self._save_state()
            return self.connect()
        self._state["aiProOnly"] = False
        self._save_state()
        return self.get_state()

    def toggle_gaming(self) -> dict:
        next_val = not bool(self._state.get("gamingOnly"))
        if next_val:
            g_list = [s for s in self._state["servers"] if s.get("isGaming")]
            if not g_list:
                g_list = [s for s in self._state["servers"] if s.get("protocol") == "WIREGUARD"]
            if not g_list:
                self._state["statusMessage"] = "No Gaming WireGuard config found"
                return self.get_state()
            self._state["gamingOnly"] = True
            self._state["aiProOnly"] = False
            self._state["mode"] = "tun"
            self._state["activeServerId"] = g_list[0]["id"]
            self._save_state()
            return self.connect()
        self._state["gamingOnly"] = False
        self._save_state()
        return self.get_state()

    def toggle_killswitch(self, enabled: bool) -> dict:
        self._state["killSwitch"] = bool(enabled)
        if not enabled:
            self._clear_killswitch_rules()
        elif self._state["connectionState"] == "CONNECTED":
            srv = self._get_active_server()
            if srv:
                self._apply_killswitch_rules(srv["host"])
        self._save_state()
        return self.get_state()

    def update_settings(self, patch: dict) -> dict:
        if "startupApp" in patch:
            self._apply_startup_registry(bool(patch["startupApp"]))
        self._state.update(patch)
        self._save_state()
        if self._state["connectionState"] == "CONNECTED" and any(
            k in patch for k in ("iranSplit", "adBlocker", "mtu", "autoMtu", "manualDns", "mux", "splitMode", "splitApps")
        ):
            return self.connect()
        return self.get_state()

    def get_running_apps(self) -> list[str]:
        ignore = {"svchost.exe", "system", "registry", "csrss.exe", "wininit.exe", "services.exe", "lsass.exe", "sing-box.exe", "echolicensevpn.exe"}
        found = set()
        for p in psutil.process_iter(["name"]):
            try:
                name = (p.info.get("name") or "").strip()
                if name.lower().endswith(".exe") and name.lower() not in ignore:
                    found.add(name)
            except Exception:
                continue
        for preset in ["chrome.exe", "msedge.exe", "firefox.exe", "Telegram.exe", "Discord.exe", "steam.exe", "cs2.exe", "VALORANT-Win64-Shipping.exe", "bf6.exe"]:
            found.add(preset)
        return sorted(found, key=str.lower)

    def browse_exe_for_split(self) -> dict:
        if not self._window:
            return self.get_state()
        result = self._window.create_file_dialog(webview.OPEN_DIALOG, file_types=("Applications (*.exe)",))
        if result:
            exe_name = Path(result[0]).name
            apps = list(self._state.get("splitApps", []))
            if exe_name not in apps:
                apps.append(exe_name)
                self._state["splitApps"] = apps
                self._save_state()
        return self.get_state()

    def dismiss_announcement(self, aid: int) -> dict:
        self._state["dismissedAnnouncementId"] = int(aid)
        self._state["announcement"] = None
        self._save_state()
        return self.get_state()

    def get_logs(self) -> str:
        try:
            if self._log_file.exists():
                return strip_ansi(self._log_file.read_text(encoding="utf-8", errors="ignore")) or "No logs yet."
        except Exception as exc:
            return f"Failed to read logs: {exc}"
        return "No logs yet."

    def open_url(self, url: str):
        webbrowser.open(url)


def main():
    api = EcholicenseApi()
    index_html = str(get_resource_dir() / "ui" / "index.html")
    win = webview.create_window(
        title="Echolicense VPN v1.0.0",
        url=index_html,
        js_api=api,
        width=460,
        height=810,
        min_size=(410, 680),
        background_color="#0A1016",
    )
    api._window = win
    webview.start(debug=False)


if __name__ == "__main__":
    main()
