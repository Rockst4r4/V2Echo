"""Build signed ad-hoc EcholicenseVPN.app and drag-and-drop .dmg on macOS runners."""

from __future__ import annotations

import os
import plistlib
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from PIL import Image


def build_icns(pc_dir: Path) -> Path:
    assets = pc_dir / "assets"
    src_icon = assets / "icon.png"
    if not src_icon.exists():
        src_icon = assets / "icon.ico"
    icns_path = assets / "icon.icns"
    iconset = pc_dir / "icon.iconset"
    if iconset.exists():
        shutil.rmtree(iconset)
    iconset.mkdir(parents=True, exist_ok=True)

    img = Image.open(src_icon).convert("RGBA")
    sizes = [16, 32, 128, 256, 512]
    for sz in sizes:
        img.resize((sz, sz), Image.Resampling.LANCZOS).save(iconset / f"icon_{sz}x{sz}.png")
        img.resize((sz * 2, sz * 2), Image.Resampling.LANCZOS).save(iconset / f"icon_{sz}x{sz}@2x.png")

    subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(icns_path)], check=True)
    shutil.rmtree(iconset, ignore_errors=True)
    return icns_path


def patch_info_plist(app_bundle: Path, version: str) -> None:
    plist_path = app_bundle / "Contents" / "Info.plist"
    with plist_path.open("rb") as fh:
        data = plistlib.load(fh)

    data["CFBundleName"] = "Echolicense VPN"
    data["CFBundleDisplayName"] = "Echolicense VPN"
    data["CFBundleIdentifier"] = "com.echolicense.vpn"
    data["CFBundleVersion"] = version
    data["CFBundleShortVersionString"] = version
    data["NSHighResolutionCapable"] = True
    data["LSMinimumSystemVersion"] = "12.0"
    data["CFBundleURLTypes"] = [
        {
            "CFBundleTypeRole": "Viewer",
            "CFBundleURLName": "com.echolicense.vpn.deeplink",
            "CFBundleURLSchemes": ["echolicense"],
        }
    ]
    with plist_path.open("wb") as fh:
        plistlib.dump(data, fh)


def main() -> None:
    arch_label = sys.argv[1] if len(sys.argv) > 1 else "AppleSilicon"
    version = sys.argv[2] if len(sys.argv) > 2 else "2.0.0"

    pc_dir = Path(__file__).resolve().parent
    repo_dir = pc_dir.parent

    # Update BUILD_EPOCH inside main.py
    mp = pc_dir / "main.py"
    ms = mp.read_text(encoding="utf-8")
    mp.write_text(re.sub(r"BUILD_EPOCH\s*=\s*\d+", f"BUILD_EPOCH = {int(time.time())}", ms, count=1), encoding="utf-8")

    icns_path = build_icns(pc_dir)

    pyi_cmd = [
        sys.executable, "-m", "PyInstaller",
        "--noconfirm",
        "--clean",
        "--onedir",
        "--windowed",
        "--name", "EcholicenseVPN",
        "--icon", str(icns_path),
        "--osx-bundle-identifier", "com.echolicense.vpn",
        "--add-data", "ui:ui",
        "--add-data", "assets:assets",
        "--hidden-import", "macos_support",
        "--hidden-import", "singbox_config",
        "--hidden-import", "webview.platforms.cocoa",
        "main.py",
    ]
    subprocess.run(pyi_cmd, cwd=str(pc_dir), check=True)

    app_bundle = pc_dir / "dist" / "EcholicenseVPN.app"
    macos_bin_dir = app_bundle / "Contents" / "MacOS"
    assert app_bundle.exists(), "PyInstaller did not generate EcholicenseVPN.app"

    # Bundle sing-box binary & all .srs rule-sets next to executable
    sb_src = pc_dir / "sing-box"
    assert sb_src.exists(), "sing-box binary not found in pc/"
    sb_dst = macos_bin_dir / "sing-box"
    shutil.copy2(sb_src, sb_dst)
    os.chmod(sb_dst, 0o755)

    for srs in pc_dir.glob("*.srs"):
        shutil.copy2(srs, macos_bin_dir / srs.name)

    patch_info_plist(app_bundle, version)

    # Ad-hoc codesign (required for Apple Silicon execution)
    subprocess.run(["codesign", "--force", "--deep", "--sign", "-", str(app_bundle)], check=True)

    # Create clean drag-and-drop DMG
    dmg_stage = pc_dir / "dmg_stage"
    if dmg_stage.exists():
        shutil.rmtree(dmg_stage)
    dmg_stage.mkdir(parents=True, exist_ok=True)

    shutil.copytree(app_bundle, dmg_stage / "EcholicenseVPN.app", symlinks=True)
    os.symlink("/Applications", dmg_stage / "Applications")

    out_dmg = repo_dir / f"Echolicense-macOS-{arch_label}-v{version}.dmg"
    if out_dmg.exists():
        out_dmg.unlink()

    subprocess.run(
        [
            "hdiutil", "create",
            "-volname", f"Echolicense VPN v{version}",
            "-srcfolder", str(dmg_stage),
            "-ov",
            "-format", "UDZO",
            str(out_dmg),
        ],
        check=True,
    )
    print(f"✅ BUILT DMG: {out_dmg}")


if __name__ == "__main__":
    main()
