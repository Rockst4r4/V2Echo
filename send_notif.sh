#!/usr/bin/env python3
import json
import subprocess
import sys
from pathlib import Path

try:
    import readline  # noqa: F401
except Exception:
    pass

if sys.stdin.isatty():
    subprocess.run(["stty", "iutf8"], stderr=subprocess.DEVNULL)

try:
    sys.stdin.reconfigure(encoding="utf-8", errors="ignore")
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def ask(prompt: str) -> str:
    try:
        return input(prompt).replace("\ufffd", "").strip()
    except UnicodeDecodeError:
        sys.stdout.write(prompt)
        sys.stdout.flush()
        return sys.stdin.buffer.readline().decode("utf-8", errors="ignore").strip()


repo_dir = Path.home() / "EcholicenseVPN"
f = repo_dir / "announcements.json"
cur = {}
if f.exists():
    try:
        cur = json.loads(f.read_text(encoding="utf-8"))
    except Exception:
        cur = {}

next_id = int(cur.get("id") or 0) + 1
print(f"\n=== Send New Notification (ID: #{next_id}) ===")
print("(Type 'off' as Title to disable the current banner)\n")

title = ask("Title: ")
if not title:
    print("Cancelled (empty title).")
    raise SystemExit(1)

if title.lower() == "off":
    payload = dict(cur)
    payload["id"] = next_id
    payload["active"] = False
    f.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    subprocess.run(["git", "add", "announcements.json"], cwd=repo_dir, check=True)
    subprocess.run(["git", "commit", "-m", f"Disable announcement #{next_id}"], cwd=repo_dir, check=True)
    subprocess.run(["git", "push", "origin", "main"], cwd=repo_dir, check=True)
    print("Done! Banner disabled for all users.")
    raise SystemExit(0)

message = ask("Message: ")
promo = ask("Promo Code (optional, press Enter to skip): ")
btn = ask("Banner Button Text (optional, e.g. Open Bot): ")
url = ask("Click URL [default: https://t.me/echopress_bot]: ") or "https://t.me/echopress_bot"

target = (ask("Target platform (all / android / pc) [default: all]: ").lower() or "all")
if target not in ("all", "android", "pc"):
    target = "all"

push_ans = (ask("Send system push notification? (y/n) [default: y]: ").lower() or "y")
banner_ans = (ask("Show in-app banner? (y/n) [default: y]: ").lower() or "y")

payload = {
    "id": next_id,
    "active": True,
    "target": target,
    "push_notification": push_ans != "n",
    "show_banner": banner_ans != "n",
    "title": title,
    "message": message,
    "promo_code": promo,
    "button_text": btn,
    "action_url": url,
    "bot_username": cur.get("bot_username") or "echopress_bot",
}

f.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"\nSaved announcements.json (ID #{next_id}). Pushing to GitHub...")

subprocess.run(["git", "add", "announcements.json"], cwd=repo_dir, check=True)
subprocess.run(["git", "commit", "-m", f"Push notification #{next_id}"], cwd=repo_dir, check=True)
subprocess.run(["git", "push", "origin", "main"], cwd=repo_dir, check=True)
print("Done! Notification published without rebuilding apps.")
