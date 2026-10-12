#!/usr/bin/env python3
"""Export every Kubera portfolio to kubera-export.json (Kubera Data API v3).

Usage (your own key + secret from Kubera → Settings → API):
    KUBERA_KEY=xxxx KUBERA_SECRET=yyyy python3 tools/kubera_export.py
Writes kubera-export.json next to this script. Read-only; nothing is changed in Kubera.
"""
import hashlib, hmac, json, os, sys, time, urllib.request

BASE = "https://api.kubera.com"
key = os.environ.get("KUBERA_KEY", "").strip()
secret = os.environ.get("KUBERA_SECRET", "").strip()
if not key or not secret:
    sys.exit("Set KUBERA_KEY and KUBERA_SECRET environment variables first.")

def get(path):
    ts = str(int(time.time()))
    sig = hmac.new(secret.encode(), (key + ts + "GET" + path).encode(), hashlib.sha256).hexdigest()
    req = urllib.request.Request(BASE + path, headers={
        "Content-Type": "application/json", "x-api-token": key, "x-timestamp": ts, "x-signature": sig})
    with urllib.request.urlopen(req, timeout=60) as r:
        o = json.load(r)
    if o.get("errorCode", 0) != 0:
        sys.exit(f"Kubera error {o.get('errorCode')}: {o.get('errorMessage', '')}")
    return o["data"]

out = []
for p in get("/api/v3/data/portfolio"):
    data = get(f"/api/v3/data/portfolio/{p['id']}")
    # cash flows (invested / withdrawn) per item, when present
    for item in data.get("asset", []) + data.get("debt", []):
        try:
            item["cashFlow"] = get(f"/api/v3/data/item/{item['id']}/cashFlow")
        except Exception:
            item["cashFlow"] = []
    out.append({"id": p["id"], "name": p.get("name"), "currency": p.get("currency"), "data": data})
    print(f"{p.get('name')}: {len(data.get('asset', []))} assets, {len(data.get('debt', []))} debts")

dst = os.path.join(os.path.dirname(os.path.abspath(__file__)), "kubera-export.json")
with open(dst, "w", encoding="utf-8") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
print("written:", dst)
