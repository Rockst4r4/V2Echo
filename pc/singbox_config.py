import sys
import base64
import json
import os
import re
from pathlib import Path
from urllib.parse import urlparse, parse_qs, unquote

IPV4_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")

AD_DOMAIN_SUFFIXES = [
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google.com",
    "pagead2.googlesyndication.com",
    "ads.youtube.com",
    "s0.2mdn.net",
    "app-measurement.com",
    "admob.com",
    "unityads.unity3d.com",
    "applovin.com",
]

IRAN_EXTRA_SUFFIXES = [
    ".ir",
    "digikala.com",
    "aparat.com",
    "filimo.com",
    "namava.ir",
    "telewebion.com",
    "varzesh3.com",
    "torob.com",
    "divar.ir",
    "snapp.ir",
    "tapsi.ir",
    "cafebazaar.ir",
    "myket.ir",
    "blubank.com",
    "shaparak.ir",
    "nobitex.ir",
    "shecan.ir",
    "rubika.ir",
    "eitaa.com",
    "bale.ai",
]


def _ensure_cidr(addr: str) -> str:
    addr = addr.strip()
    if "/" in addr:
        return addr
    return f"{addr}/128" if ":" in addr else f"{addr}/32"


def _parse_reserved(raw: str):
    cleaned = raw.strip().lstrip("[").rstrip("]").strip()
    if not cleaned:
        return None
    if "," in cleaned:
        nums = [int(x.strip()) for x in cleaned.split(",") if x.strip().isdigit()]
        if len(nums) == 3 and all(0 <= n <= 255 for n in nums):
            return nums
    try:
        b = base64.b64decode(cleaned)
        if len(b) == 3:
            return list(b)
    except Exception:
        pass
    return None


def build_outbound(profile: dict, mtu: int, mux_cfg: dict) -> dict:
    proto = profile["protocol"].upper()
    raw = profile["rawConfig"]

    if proto == "WIREGUARD":
        section = ""
        priv_key, pub_key, psk, endpoint, reserved_raw = "", "", "", "", ""
        addresses = []
        for line in raw.splitlines():
            line = line.split("#", 1)[0].split(";", 1)[0].strip()
            if not line:
                continue
            if line.startswith("[") and line.endswith("]"):
                section = line.lower()
                continue
            if "=" not in line:
                continue
            k, v = [x.strip() for x in line.split("=", 1)]
            kl = k.lower()
            if section == "[interface]":
                if kl == "privatekey":
                    priv_key = v
                elif kl == "address":
                    addresses.extend([_ensure_cidr(a) for a in v.split(",") if a.strip()])
                elif kl == "reserved":
                    reserved_raw = v
            elif section == "[peer]":
                if kl == "publickey":
                    pub_key = v
                elif kl == "presharedkey":
                    psk = v
                elif kl == "endpoint":
                    endpoint = v
                elif kl == "reserved" and not reserved_raw:
                    reserved_raw = v

        host = profile["host"]
        port = int(profile["port"])
        if endpoint:
            if endpoint.startswith("[") and "]:" in endpoint:
                host, p = endpoint[1:].split("]:", 1)
                port = int(p)
            elif ":" in endpoint:
                host, p = endpoint.rsplit(":", 1)
                port = int(p)

        out = {
            "type": "wireguard",
            "tag": "proxy",
            "server": host,
            "server_port": port,
            "local_address": addresses or ["10.0.0.2/32"],
            "private_key": priv_key,
            "peer_public_key": pub_key,
            "mtu": max(1200, min(1500, int(mtu))),
        }
        if psk:
            out["pre_shared_key"] = psk
        res_bytes = _parse_reserved(reserved_raw)
        if res_bytes:
            out["reserved"] = res_bytes
        return out

    if proto == "VMESS":
        b64 = raw.replace("vmess://", "", 1).strip()
        pad = "=" * ((4 - len(b64) % 4) % 4)
        obj = json.loads(base64.b64decode(b64 + pad).decode("utf-8"))
        aid_raw = str(obj.get("aid") or "0").strip()
        out = {
            "type": "vmess",
            "tag": "proxy",
            "server": str(obj.get("add") or profile["host"]),
            "server_port": int(str(obj.get("port") or profile["port"])),
            "uuid": str(obj.get("id") or ""),
            "security": str(obj.get("scy") or "auto") or "auto",
            "alter_id": int(aid_raw) if aid_raw.isdigit() else 0,
        }
        net = (obj.get("net") or "tcp").lower()
        tls = (obj.get("tls") or "").lower()
        host_hdr = obj.get("host") or out["server"]
        path = obj.get("path") or "/"
        sni = obj.get("sni") or host_hdr
        if tls == "tls":
            out["tls"] = {"enabled": True, "server_name": sni, "utls": {"enabled": True, "fingerprint": "chrome"}}
        if net == "ws":
            out["transport"] = {"type": "ws", "path": path, "headers": {"Host": host_hdr}}
        elif net == "grpc":
            out["transport"] = {"type": "grpc", "service_name": path}
        elif net == "httpupgrade":
            out["transport"] = {"type": "httpupgrade", "path": path, "host": host_hdr}
        if mux_cfg.get("enabled"):
            out["multiplex"] = {
                "enabled": True,
                "protocol": "h2mux",
                "max_connections": int(mux_cfg.get("concurrency", 8)),
                "min_streams": 4,
            }
        return out

    base = raw.split("#", 1)[0].replace(" ", "%20")
    u = urlparse(base)
    params = {k.lower(): unquote(v[0]) for k, v in parse_qs(u.query).items() if v}
    secret = unquote(u.username or "")
    net = params.get("type", "tcp").lower()
    sec = params.get("security", "none").lower()
    host = u.hostname or profile["host"]
    port = u.port or int(profile["port"])

    if proto == "TROJAN":
        out = {
            "type": "trojan",
            "tag": "proxy",
            "server": host,
            "server_port": port,
            "password": secret,
        }
    else:
        out = {
            "type": "vless",
            "tag": "proxy",
            "server": host,
            "server_port": port,
            "uuid": secret,
        }
        flow = params.get("flow", "").strip()
        if flow and not (mux_cfg.get("enabled") and "vision" in flow.lower()):
            out["flow"] = flow

    if sec == "reality":
        out["tls"] = {
            "enabled": True,
            "server_name": params.get("sni", ""),
            "utls": {"enabled": True, "fingerprint": params.get("fp", "chrome") or "chrome"},
            "reality": {
                "enabled": True,
                "public_key": params.get("pbk", ""),
                "short_id": params.get("sid", ""),
            },
        }
    elif sec == "tls":
        out["tls"] = {
            "enabled": True,
            "server_name": params.get("sni", host) or host,
            "insecure": params.get("allowinsecure") == "1" or params.get("insecure") == "1",
            "utls": {"enabled": True, "fingerprint": params.get("fp", "chrome") or "chrome"},
        }
        if params.get("alpn"):
            out["tls"]["alpn"] = [a.strip() for a in params["alpn"].split(",") if a.strip()]

    if net == "ws":
        out["transport"] = {
            "type": "ws",
            "path": params.get("path", "/"),
            "headers": {"Host": params.get("host", host)},
        }
    elif net == "grpc":
        out["transport"] = {"type": "grpc", "service_name": params.get("servicename", "")}
    elif net == "httpupgrade":
        out["transport"] = {
            "type": "httpupgrade",
            "path": params.get("path", "/"),
            "host": params.get("host", host),
        }

    if mux_cfg.get("enabled"):
        out["multiplex"] = {
            "enabled": True,
            "protocol": "h2mux",
            "max_connections": int(mux_cfg.get("concurrency", 8)),
            "min_streams": 4,
        }
    return out


AI_SRS_FILES = [
    ("geosite-ai-cat", "geosite-category-ai-!cn.srs"),
    ("geosite-gemini", "geosite-google-gemini.srs"),
    ("geosite-deepmind", "geosite-google-deepmind.srs"),
    ("geosite-openai", "geosite-openai.srs"),
    ("geosite-anthropic", "geosite-anthropic.srs"),
    ("geosite-perplexity", "geosite-perplexity.srs"),
    ("geosite-xai", "geosite-xai.srs"),
    ("geosite-binance", "geosite-binance.srs"),
    ("geosite-okx", "geosite-okx.srs"),
    ("geosite-bybit", "geosite-bybit.srs"),
]

AI_EXTRA_SUFFIXES = [
    "gemini.google.com", "bard.google.com", "aistudio.google.com", "makersuite.google.com",
    "generativelanguage.googleapis.com", "ai.google.dev", "deepmind.google", "deepmind.com",
    "alkalimakersuite-pa.clients6.google.com", "robinfrontend-pa.clients6.google.com",
    "proactivebackend-pa.clients6.google.com", "waa-pa.clients6.google.com",
    "geller-pa.googleapis.com", "clients6.google.com", "ogs.google.com",
    "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com",
    "anthropic.com", "claude.ai", "perplexity.ai", "x.ai", "grok.com",
    "copilot.microsoft.com", "cursor.com", "cursor.sh",
    "binance.com", "bybit.com", "okx.com",
]

def build_singbox_config(
    profile: dict,
    mode: str,
    dns_server: str,
    mtu: int,
    iran_split: bool,
    adblock: bool,
    mux_cfg: dict,
    split_mode: str,
    split_apps: list[str],
    socks_port: int = 10808,
    tun_stack: str = "system",
    app_dir: str | None = None,
    ai_profile: dict | None = None,
) -> dict:
    proxy_out = build_outbound(profile, mtu, mux_cfg)
    dns_remote = (dns_server or "").strip()
    if not dns_remote:
        dns_remote = "https://dns.adguard-dns.com/dns-query" if adblock else "https://1.1.1.1/dns-query"

    remote_dns_entry = {
        "tag": "remote",
        "address": dns_remote,
        "detour": "proxy",
    }
    if not IPV4_RE.match(dns_remote):
        remote_dns_entry["address_resolver"] = "local"

    base_dir = Path(app_dir) if app_dir else Path(".")
    geoip_ir = base_dir / "geoip-ir.srs"
    geosite_ir = base_dir / "geosite-ir.srs"
    rule_sets = []
    if iran_split and geoip_ir.exists() and geoip_ir.stat().st_size > 100:
        rule_sets.append({"tag": "geoip-ir", "type": "local", "format": "binary", "path": str(geoip_ir)})
    if iran_split and geosite_ir.exists() and geosite_ir.stat().st_size > 100:
        rule_sets.append({"tag": "geosite-ir", "type": "local", "format": "binary", "path": str(geosite_ir)})
    rs_tags = [r["tag"] for r in rule_sets]
    ai_out = None
    ai_rs_tags = []
    if ai_profile and ai_profile.get("id") != profile.get("id"):
        try:
            ai_out = build_outbound(ai_profile, mtu, {})
            ai_out["tag"] = "ai-proxy"
            for tag_name, fname in AI_SRS_FILES:
                fpath = base_dir / fname
                if fpath.exists() and fpath.stat().st_size > 100:
                    rule_sets.append({"tag": tag_name, "type": "local", "format": "binary", "path": str(fpath)})
                    ai_rs_tags.append(tag_name)
        except Exception:
            ai_out = None

    dns_rules = [{"outbound": "any", "server": "local"}]
    if ai_out:
        if ai_rs_tags:
            dns_rules.append({"rule_set": ai_rs_tags, "server": "ai-dns"})
        dns_rules.append({"domain_suffix": AI_EXTRA_SUFFIXES, "server": "ai-dns"})
    if iran_split:
        dns_rules.append({"domain_suffix": IRAN_EXTRA_SUFFIXES, "server": "local"})
        if "geosite-ir" in rs_tags:
            dns_rules.append({"rule_set": ["geosite-ir"], "server": "local"})
    if adblock:
        dns_rules.append({"domain_suffix": AD_DOMAIN_SUFFIXES, "server": "block"})

    inbounds = [
        {
            "type": "mixed",
            "tag": "mixed-in",
            "listen": "127.0.0.1",
            "listen_port": socks_port,
            "sniff": True,
        }
    ]

    if mode == "tun":
        tun_inb = {
            "type": "tun",
            "tag": "tun-in",
            "address": ["172.19.0.1/30"],
            "mtu": max(1200, min(1500, int(mtu))),
            "auto_route": True,
            "strict_route": False,
            "stack": tun_stack,
            "sniff": True,
        }
        if sys.platform != "darwin":
            tun_inb["interface_name"] = "EcholicenseTUN"
        inbounds.append(tun_inb)

    route_rules = [
        {"protocol": "dns", "outbound": "dns-out"},
        {"ip_is_private": True, "outbound": "direct"},
        {"process_name": ["EcholicenseVPN.exe", "EcholicenseVPN", "python.exe", "pythonw.exe", "python3"], "outbound": "direct"},
    ]
    if ai_out:
        if ai_rs_tags:
            route_rules.append({"network": "udp", "port": 443, "rule_set": ai_rs_tags, "outbound": "block"})
            route_rules.append({"rule_set": ai_rs_tags, "outbound": "ai-proxy"})
        route_rules.append({"network": "udp", "port": 443, "domain_suffix": AI_EXTRA_SUFFIXES, "outbound": "block"})
        route_rules.append({"domain_suffix": AI_EXTRA_SUFFIXES, "outbound": "ai-proxy"})

    if adblock:
        route_rules.append({"domain_suffix": AD_DOMAIN_SUFFIXES, "outbound": "block"})

    if iran_split:
        route_rules.append({"domain_suffix": IRAN_EXTRA_SUFFIXES, "outbound": "direct"})
        if rs_tags:
            route_rules.append({"rule_set": rs_tags, "outbound": "direct"})

    clean_apps = [a.strip() for a in (split_apps or []) if a.strip()]
    final_outbound = "proxy"

    if clean_apps:
        if split_mode == "only":
            route_rules.append({"process_name": clean_apps, "outbound": "proxy"})
            final_outbound = "direct"
        else:
            route_rules.append({"process_name": clean_apps, "outbound": "direct"})

    route_obj = {
        "auto_detect_interface": True,
        "rules": route_rules,
        "final": final_outbound,
    }
    if rule_sets:
        route_obj["rule_set"] = rule_sets

    cfg = {
        "log": {"level": "warn", "timestamp": True},
        "dns": {
            "servers": [
                remote_dns_entry,
                {"tag": "local", "address": "178.22.122.100", "detour": "direct"},
                {"tag": "block", "address": "rcode://success"},
            ],
            "rules": dns_rules,
            "strategy": "ipv4_only",
        },
        "inbounds": inbounds,
        "outbounds": [
            proxy_out,
            {"type": "direct", "tag": "direct"},
            {"type": "block", "tag": "block"},
            {"type": "dns", "tag": "dns-out"},
        ],
        "route": route_obj,
    }
    if ai_out:
        cfg.setdefault("dns", {}).setdefault("servers", []).append(
            {"tag": "ai-dns", "address": "1.1.1.1", "detour": "ai-proxy"}
        )
        cfg.setdefault("outbounds", []).append(ai_out)
    return cfg
