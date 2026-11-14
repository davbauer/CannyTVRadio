#!/usr/bin/env python3
"""
Publishes a CannyTVRadio release on GitHub.

    python3 scripts/release.py

Reads the version from app/build.gradle.kts and the notes from release-notes/<version>.json,
runs the tests, builds the release APK, checks it carries the release certificate, writes
update.json (what the app reads to offer updates) and creates the GitHub release with:

  CannyTVRadio.apk            stable name, so .../releases/latest/download/CannyTVRadio.apk
                              always gives the newest version (for the Downloader app)
  CannyTVRadio-<version>.apk  versioned copy, the one update.json points to
  update.json                 version, changes and downloads with SHA-256 and size

Uses the GitHub credential that git already has for github.com.
"""
import datetime, hashlib, json, os, re, subprocess, sys, urllib.request

REPO = "davbauer/CannyTVRadio"
RELEASE_CERT = "ffbe18d85267d7c1a98ddb41175faeec3d9cc38f3086e160b8c833ab4c848001"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

gradle = open("app/build.gradle.kts").read()
version = re.search(r'versionName = "([^"]+)"', gradle).group(1)
code = int(re.search(r"versionCode = (\d+)", gradle).group(1))
min_sdk = int(re.search(r"minSdk = (\d+)", gradle).group(1))
notes = json.load(open(f"release-notes/{version}.json"))
tag = f"v{version}"
print(f"Releasing {version} (code {code})")

env = dict(os.environ, JAVA_HOME=os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17"))
subprocess.run(["./gradlew", "-q", "testReleaseUnitTest", "assembleRelease"], check=True, env=env)
apk = "app/build/outputs/apk/release/app-release.apk"

sdk = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
tools = sorted(os.listdir(f"{sdk}/build-tools"))[-1]
certs = subprocess.run([f"{sdk}/build-tools/{tools}/apksigner", "verify", "--print-certs", apk],
                       check=True, capture_output=True, text=True).stdout
if RELEASE_CERT not in certs:
    sys.exit("The APK is not signed with the release key. Is ~/.gradle/gradle.properties set up?")

data = open(apk, "rb").read()
sha = hashlib.sha256(data).hexdigest()
os.makedirs("dist", exist_ok=True)
versioned = f"CannyTVRadio-{version}.apk"
for name in (versioned, "CannyTVRadio.apk"):
    open(f"dist/{name}", "wb").write(data)
update = {
    "app": "CannyTVRadio",
    "versionCode": code,
    "versionName": version,
    "published": datetime.date.today().isoformat(),
    "changes": notes,
    # One universal APK covers every processor today; more targets can be added here later.
    "downloads": [{
        "target": "universal",
        "abis": [],
        "minSdk": min_sdk,
        "url": f"https://github.com/{REPO}/releases/download/{tag}/{versioned}",
        "sha256": sha,
        "size": len(data),
    }],
}
json.dump(update, open("dist/update.json", "w"), ensure_ascii=False, indent=2)

token = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
                       capture_output=True, text=True, check=True).stdout
token = re.search(r"^password=(.*)$", token, re.M).group(1)

def api(method, url, body=None, content_type="application/json"):
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": content_type})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b"{}")

latest = f"https://github.com/{REPO}/releases/latest/download/CannyTVRadio.apk"
bullets = lambda items: "\n".join(f"- {c}" for c in items)
body = f"""## Install on Android TV, Google TV or Fire TV

Open the **Downloader** app on your TV and enter:

```
{latest}
```

Then open the downloaded file and install it. To update later, open the same address again, or use **Settings → Updates** inside the app.

Works on Android 5.0 or newer, on ARM and x86 devices: TVs, TV boxes, Fire TV sticks, tablets, phones and Chromebooks.

## What's new

{bullets(notes["en"])}

## Neu in dieser Version

{bullets(notes["de"])}

SHA-256 of `{versioned}`: `{sha}`
"""
release = api("POST", f"https://api.github.com/repos/{REPO}/releases", json.dumps({
    "tag_name": tag, "target_commitish": "main", "name": f"CannyTVRadio {version}",
    "body": body, "make_latest": "true"}).encode())
upload = release["upload_url"].split("{")[0]
for name, ctype in ((versioned, "application/vnd.android.package-archive"),
                    ("CannyTVRadio.apk", "application/vnd.android.package-archive"),
                    ("update.json", "application/json")):
    api("POST", f"{upload}?name={name}", open(f"dist/{name}", "rb").read(), ctype)
    print(f"  uploaded {name}")
print(release["html_url"])
