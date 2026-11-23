#!/usr/bin/env python3
"""下载并解压便携工具链（JDK17 + android.jar + d8）。"""
import os
import shutil
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
TC = os.path.join(ROOT, "toolchain")
LIB = os.path.join(ROOT, "lib")
os.makedirs(TC, exist_ok=True)
os.makedirs(LIB, exist_ok=True)

JDK_URL = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
PLATFORM_URL = "https://dl.google.com/android/repository/platform-36_r02.zip"
R8_URL = "https://dl.google.com/dl/android/maven2/com/android/tools/r8/9.5.33/r8-9.5.33.jar"


def download(url, dest):
    if os.path.isfile(dest) and os.path.getsize(dest) > 1024:
        print(f"已存在，跳过：{dest}", flush=True)
        return
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(dest, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        step = 8 * 1024 * 1024
        while True:
            chunk = resp.read(1024 * 256)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            if done % step < 256 * 1024:
                pct = f"{done * 100 // total}%" if total else f"{done // 1048576}MB"
                print(f"  {os.path.basename(dest)} {pct}", flush=True)
    print(f"完成：{os.path.basename(dest)} {os.path.getsize(dest) // 1048576} MB", flush=True)


print("== 下载 JDK 17", flush=True)
download(JDK_URL, os.path.join(TC, "jdk.zip"))
print("== 下载 Android platform 36", flush=True)
download(PLATFORM_URL, os.path.join(TC, "platform-36.zip"))
print("== 下载 r8 (d8)", flush=True)
download(R8_URL, os.path.join(LIB, "d8.jar"))

print("== 解压 JDK", flush=True)
jdk_dir = None
if not any(d.startswith("jdk-") for d in os.listdir(TC) if os.path.isdir(os.path.join(TC, d))):
    with zipfile.ZipFile(os.path.join(TC, "jdk.zip")) as z:
        z.extractall(TC)
for d in sorted(os.listdir(TC)):
    if d.startswith("jdk-") and os.path.isdir(os.path.join(TC, d)):
        jdk_dir = os.path.join(TC, d)
print("JDK:", jdk_dir, flush=True)

print("== 提取 android.jar", flush=True)
target = os.path.join(LIB, "android.jar")
if not os.path.isfile(target):
    with zipfile.ZipFile(os.path.join(TC, "platform-36.zip")) as z:
        names = [n for n in z.namelist() if n.endswith("android.jar")]
        if not names:
            print("平台包里没找到 android.jar", flush=True)
            sys.exit(1)
        with z.open(names[0]) as src, open(target, "wb") as dst:
            shutil.copyfileobj(src, dst)
print("android.jar:", os.path.getsize(target) // 1048576, "MB", flush=True)

javac = os.path.join(jdk_dir, "bin", "javac.exe")
print("== 校验 javac", flush=True)
os.system(f'"{javac}" -version')
print("全部就绪", flush=True)
