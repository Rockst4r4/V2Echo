#!/usr/bin/env bash
set -e
cd ~/EcholicenseVPN

python3 - << 'PY_SEND'
import json
from pathlib import Path

f = Path("announcements.json")
cur = {}
if f.exists():
    try:
        cur = json.loads(f.read_text(encoding="utf-8"))
    except Exception:
        cur = {}

next_id = int(cur.get("id") or 0) + 1
print(f"\n=== ارسال نوتیفیکیشن جدید (ID: #{next_id}) ===")
title = input("عنوان نوتیفیکیشن (Title): ").strip()
if not title:
    print("لغو شد (عنوان خالی است).")
    raise SystemExit(1)

message = input("متن پیام (Message): ").strip()
promo = input("کد تخفیف (اختیاری - برای خالی Enter بزنید): ").strip()
btn = input("متن دکمه داخل بنر (مثلاً ورود به ربات - اختیاری): ").strip()
url = input("لینک مقصد با کلیک روی نوتیف (پیش‌فرض https://t.me/echopress_bot): ").strip() or "https://t.me/echopress_bot"
target = (input("ارسال برای (all / android / pc) [پیش‌فرض all]: ").strip().lower() or "all")
if target not in ("all", "android", "pc"):
    target = "all"

push_ans = (input("ارسال نوتیفیکیشن سیستمی روی گوشی/ویندوز؟ (y/n) [پیش‌فرض y]: ").strip().lower() or "y")
banner_ans = (input("نمایش بنر داخل صفحه اصلی اپ هم باشد؟ (y/n) [پیش‌فرض y]: ").strip().lower() or "y")

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
print(f"\n✅ فایل announcements.json با شناسه #{next_id} ذخیره شد.")
PY_SEND

git add announcements.json
git commit -m "Push notification update"
git push origin main
echo "🚀 نوتیفیکیشن با موفقیت منتشر شد! (بدون بیلد مجدد اپ)"
