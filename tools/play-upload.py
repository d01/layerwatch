#!/usr/bin/env python3
"""Upload a release bundle to a Google Play testing track.

Uses the Play Developer Publishing API with a service account key. Standard library
only; the JWT is signed with the openssl CLI.

    tools/play-upload.py                      # release AAB -> internal track
    tools/play-upload.py --track alpha --notes "What changed"

The key defaults to ~/.android/layerwatch-play-api.json (override with --key or
LAYERWATCH_PLAY_KEY). Keep it out of the repo.
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

PACKAGE = "app.layerwatch"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/" + PACKAGE
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_AAB = os.path.join(ROOT, "app/build/outputs/bundle/release/app-release.aab")
DEFAULT_KEY = os.environ.get("LAYERWATCH_PLAY_KEY", os.path.expanduser("~/.android/layerwatch-play-api.json"))


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def access_token(key: dict) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": key["client_email"], "scope": SCOPE, "aud": key["token_uri"],
        "iat": now, "exp": now + 3600,
    }).encode())
    unsigned = f"{header}.{claims}".encode()
    with tempfile.NamedTemporaryFile("w", suffix=".pem") as pem:
        os.chmod(pem.name, 0o600)
        pem.write(key["private_key"])
        pem.flush()
        sig = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", pem.name],
            input=unsigned, capture_output=True, check=True,
        ).stdout
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": f"{header}.{claims}.{b64url(sig)}",
    }).encode()
    return call("POST", key["token_uri"], body, "application/x-www-form-urlencoded")["access_token"]


def call(method, url, body=None, content_type="application/json", token=None):
    req = urllib.request.Request(url, data=body, method=method)
    if body is not None:
        req.add_header("Content-Type", content_type)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {url.split('?')[0]} failed ({e.code}):\n{e.read().decode(errors='replace')}")
    return json.loads(raw) if raw else {}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--aab", default=DEFAULT_AAB)
    ap.add_argument("--key", default=DEFAULT_KEY)
    ap.add_argument("--track", default="internal", help="internal, alpha (closed), beta (open) or production")
    ap.add_argument("--notes", help="release notes (en-US)")
    ap.add_argument("--draft", action="store_true", help="leave the release as a draft in Play Console")
    args = ap.parse_args()

    with open(args.key) as f:
        token = access_token(json.load(f))
    edit = call("POST", f"{API}/edits", b"{}", token=token)["id"]

    with open(args.aab, "rb") as f:
        print(f"Uploading {os.path.relpath(args.aab, ROOT)}…")
        bundle = call("POST", f"{UPLOAD}/edits/{edit}/bundles?uploadType=media", f.read(),
                      "application/octet-stream", token)
    code = bundle["versionCode"]

    release = {"versionCodes": [str(code)], "status": "draft" if args.draft else "completed"}
    if args.notes:
        release["releaseNotes"] = [{"language": "en-US", "text": args.notes}]
    call("PUT", f"{API}/edits/{edit}/tracks/{args.track}",
         json.dumps({"track": args.track, "releases": [release]}).encode(), token=token)
    call("POST", f"{API}/edits/{edit}:commit", token=token)
    print(f"versionCode {code} is on the {args.track} track" + (" as a draft" if args.draft else ""))


if __name__ == "__main__":
    main()
