#!/usr/bin/env python3
"""Download Android command-line tools from mirror."""
import urllib.request
import os
import sys
import time

def download_with_progress(url, dest):
    """Download with progress reporting."""
    def report(block_num, block_size, total_size):
        downloaded = block_num * block_size / (1024 * 1024)
        total = max(total_size / (1024 * 1024), 1)
        pct = min(downloaded / total * 100, 100)
        print(f"\r  {downloaded:.0f}/{total:.0f} MB ({pct:.0f}%)", end="", flush=True)
    
    print(f"Downloading: {url}")
    urllib.request.urlretrieve(url, dest, reporthook=report)
    size_mb = os.path.getsize(dest) / (1024 * 1024)
    print(f"\nDone: {size_mb:.1f} MB")
    return size_mb

sdk_dir = os.path.join(os.environ['LOCALAPPDATA'], 'Android', 'Sdk')
dest = os.path.join(sdk_dir, 'cmdline-tools.zip')

# Try mirrors first (faster in China), then Google
urls = [
    "https://mirrors.ustc.edu.cn/android/repository/commandlinetools-win-11076708_latest.zip",
    "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip",
]

for url in urls:
    try:
        size = download_with_progress(url, dest)
        if size > 100:
            print("SUCCESS")
            sys.exit(0)
    except Exception as e:
        print(f"\nFailed: {e}")
        if os.path.exists(dest):
            os.remove(dest)

print("ALL_FAILED")
sys.exit(1)
