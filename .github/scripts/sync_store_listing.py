#!/usr/bin/env python3
"""Push the store listing text and images straight to the Play Developer API,
bypassing the Play Console UI entirely.

Needs PLAY_SERVICE_ACCOUNT_JSON (same secret publish.yml / play-status.yml
use) with the "Manage store presence" permission in Play Console -> Users
and permissions — a separate grant from the "Release apps to testing tracks"
permission the publish workflow needs.

Env vars:
  PLAY_SERVICE_ACCOUNT_JSON  service-account JSON key (required)
  PACKAGE                    application id, e.g. com.mikmy.fuse (required)
  TITLE                      store listing title (required)
  SHORT_DESCRIPTION          <=80 chars (required)
  FULL_DESCRIPTION           <=4000 chars (required)
  LANGUAGE                   Play listing language, default en-US
  SCREENSHOT_COUNT           how many artifacts/shots/*.png to upload (2-8), default 4
  ICON                       path to the 512x512 icon PNG, default store/icon-512.png
  FEATURE_GRAPHIC            path to the feature graphic PNG, default
                             store/feature-graphic-1024x500.png
  SCREENSHOTS_DIR            directory of captured screenshots, default artifacts/shots
"""
import glob
import json
import os
import sys

import requests
from google.auth.transport.requests import Request
from google.oauth2 import service_account

PKG = os.environ["PACKAGE"]
TITLE = os.environ["TITLE"]
SHORT_DESCRIPTION = os.environ["SHORT_DESCRIPTION"]
FULL_DESCRIPTION = os.environ["FULL_DESCRIPTION"]
LANGUAGE = os.environ.get("LANGUAGE", "en-US")
SCREENSHOT_COUNT = int(os.environ.get("SCREENSHOT_COUNT", "4"))
ICON = os.environ.get("ICON", "store/icon-512.png")
FEATURE_GRAPHIC = os.environ.get("FEATURE_GRAPHIC", "store/feature-graphic-1024x500.png")
SCREENSHOTS_DIR = os.environ.get("SCREENSHOTS_DIR", "artifacts/shots")

info = json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"])
creds = service_account.Credentials.from_service_account_info(
    info, scopes=["https://www.googleapis.com/auth/androidpublisher"]
)
creds.refresh(Request())
H = {"Authorization": f"Bearer {creds.token}"}

BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"
UPLOAD_BASE = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PKG}"

print(f"service account: {info.get('client_email')}")
print(f"package: {PKG}, language: {LANGUAGE}")

r = requests.post(f"{BASE}/edits", headers=H)
if r.status_code != 200:
    print(f"::error::edits.insert {r.status_code}: {r.text}")
    sys.exit(1)
edit_id = r.json()["id"]
print(f"opened edit {edit_id}")

lr = requests.get(f"{BASE}/edits/{edit_id}/listings", headers=H)
if lr.status_code == 200:
    listings = lr.json().get("listings", [])
    if listings:
        print("existing listings on Play for this app:")
        for l in listings:
            print(f"  language={l.get('language')!r} title={l.get('title')!r}")
    else:
        print("existing listings on Play for this app: none at all")
else:
    print(f"  (listings.list: {lr.status_code} {lr.text})")


def upload_image(image_type, path):
    with open(path, "rb") as f:
        data = f.read()
    url = f"{UPLOAD_BASE}/edits/{edit_id}/listings/{LANGUAGE}/{image_type}"
    resp = requests.post(
        url,
        headers={**H, "Content-Type": "image/png"},
        params={"uploadType": "media"},
        data=data,
    )
    if resp.status_code != 200:
        raise RuntimeError(
            f"upload {image_type} {os.path.basename(path)} failed: "
            f"{resp.status_code} {resp.text}"
        )
    print(f"  uploaded {image_type}: {os.path.basename(path)} ({len(data)} bytes)")


try:
    listing_body = {
        "language": LANGUAGE,
        "title": TITLE,
        "shortDescription": SHORT_DESCRIPTION,
        "fullDescription": FULL_DESCRIPTION,
    }
    pr = requests.put(
        f"{BASE}/edits/{edit_id}/listings/{LANGUAGE}", headers=H, json=listing_body
    )
    if pr.status_code != 200:
        raise RuntimeError(f"listings.update failed: {pr.status_code} {pr.text}")
    print(f"  set listing text: title={TITLE!r}")

    # Clear anything uploaded by a previous run of this workflow so reruns
    # don't pile up duplicates.
    for image_type in ("icon", "featureGraphic", "phoneScreenshots"):
        dr = requests.delete(
            f"{BASE}/edits/{edit_id}/listings/{LANGUAGE}/{image_type}", headers=H
        )
        if dr.status_code not in (200, 204):
            print(f"  (deleteall {image_type}: {dr.status_code}, continuing)")

    if not os.path.isfile(ICON):
        raise RuntimeError(f"missing {ICON}")
    upload_image("icon", ICON)

    if not os.path.isfile(FEATURE_GRAPHIC):
        raise RuntimeError(f"missing {FEATURE_GRAPHIC}")
    upload_image("featureGraphic", FEATURE_GRAPHIC)

    shots = sorted(glob.glob(os.path.join(SCREENSHOTS_DIR, "*.png")))[:SCREENSHOT_COUNT]
    if len(shots) < 2:
        raise RuntimeError(
            f"only found {len(shots)} screenshots in {SCREENSHOTS_DIR}, need >= 2"
        )
    for shot in shots:
        upload_image("phoneScreenshots", shot)

    cr = requests.post(f"{BASE}/edits/{edit_id}:commit", headers=H)
    if cr.status_code != 200:
        raise RuntimeError(f"edits.commit failed: {cr.status_code} {cr.text}")
    print("edit committed — store listing text and images are live")
except Exception as e:
    print(f"::error::{e}")
    requests.delete(f"{BASE}/edits/{edit_id}", headers=H)
    print("edit discarded, nothing changed on Play")
    sys.exit(1)
