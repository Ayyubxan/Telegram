#!/usr/bin/env python3
"""Convert kubera-export.json into a Telegram-fork backup file that Settings → Mods → Restore from file accepts.

    python3 tools/kubera_to_backup.py tools/kubera-export.json [out.json]

Mapping: first / "Main" portfolio → Personal, every other portfolio → a new business (entity);
bank / cash sheets → accounts; a "Qarzdor" (debtors) sheet → "Owed to me"; Kubera debts → "I owe";
everything else → assets grouped by sheet; a known ticker with a quantity (USD, BTC, XAU, AAPL…) → priced live.
"""
import json, os, re, sys, time, uuid

KNOWN_FIAT = {"UZS","USD","EUR","RUB","GBP","KZT","KGS","TJS","TRY","AED","CNY","KRW","JPY","CHF","SAR"}
KNOWN_CRYPTO = {"BTC","ETH","TON","USDT","SOL","BNB"}
KNOWN_METAL = {"XAU","XAG"}
GROUP_WORDS = [
    ("realty", ["real", "estate", "build", "uy", "kvartira", "own", "land", "yer"]),
    ("vehicles", ["car", "vehicle", "mashina", "avto"]),
    ("crypto", ["crypto"]),
    ("stocks", ["stock", "fund", "etf", "aksiya"]),
    ("metals", ["gold", "silver", "metal", "commodit", "oltin", "kumush"]),
    ("business", ["business", "biznes", "company"]),
    ("cash", ["cash", "bank", "deposit", "naqd"]),
]
EMOJI = {"cash": "💵", "metals": "🥇", "crypto": "₿", "stocks": "📈", "realty": "🏠", "vehicles": "🚗", "business": "🏪", "other": "💎"}

def low(*parts):
    return " ".join((p or "") for p in parts).lower()

def group_for(it):
    s = low(it.get("sheetName"), it.get("sectionName"), it.get("tickerSubType"), it.get("subType"))
    for g, words in GROUP_WORDS:
        if any(w in s for w in words):
            return g
    return "other"

def is_bank(it):
    s = low(it.get("sheetName"), it.get("sectionName"))
    return any(w in s for w in ["bank", "cash", "naqd", "karta", "card", "hisob"])

def is_debtor(it):
    s = low(it.get("sheetName"), it.get("sectionName"))
    return any(w in s for w in ["qarzdor", "debtor", "owed", "receivable", "lent"])

def quoted_code(it):
    t = (it.get("ticker") or "").upper()
    q = it.get("quantity") or 0
    if not t or q <= 0:
        return None
    cur = (it.get("value") or {}).get("currency", "")
    sub = low(it.get("tickerSubType"), it.get("subType"))
    if t in KNOWN_FIAT and t != cur:
        return t
    if t in KNOWN_CRYPTO or t in KNOWN_METAL:
        return t
    if t not in KNOWN_FIAT and any(w in sub for w in ["stock", "fund", "etf"]) and re.fullmatch(r"[A-Z][A-Z0-9.\-]{0,9}", t):
        return t
    return None

def notes(it):
    parts = [x.strip() for x in [it.get("description"), it.get("note")] if x and x.strip()]
    sheet, section = (it.get("sheetName") or "").strip(), (it.get("sectionName") or "").strip()
    if sheet or section:
        parts.append("Kubera: " + sheet + (" / " + section if section else ""))
    return "\n".join(parts)

src = sys.argv[1] if len(sys.argv) > 1 else "kubera-export.json"
dst = sys.argv[2] if len(sys.argv) > 2 else "kubera-import.json"
portfolios = json.load(open(src, encoding="utf-8"))
now = int(time.time() * 1000)
today = int(time.time() // 86400)
rows = {"entity": [], "account": [], "asset": [], "asset_value": [], "debt": []}
stats = {"entities": 0, "accounts": 0, "assets": 0, "debts": 0, "skipped": 0}
nid = lambda: uuid.uuid4().hex[:12]

for idx, p in enumerate(portfolios):
    data = p["data"]
    if idx == 0 or (p.get("name") or "").lower() == "main":
        entity = "personal"
    else:
        entity = nid()
        rows["entity"].append({"id": entity, "name": p.get("name"), "emoji": "🏪", "color": "", "sort": 10 + idx, "archived": 0, "del": 0, "updated": now})
        stats["entities"] += 1
    for it in data.get("asset", []):
        if it.get("parent"):
            stats["skipped"] += 1
            continue
        val = it.get("value") or {}
        amount, cur = val.get("amount", 0) or 0, val.get("currency") or p.get("currency") or "USD"
        name, note = it.get("name") or "Asset", notes(it)
        if is_debtor(it):
            rows["debt"].append({"id": nid(), "dir": 0, "name": name, "user_id": 0, "amount": amount, "currency": cur, "day": today, "due": 0, "note": note, "entity": entity, "closed": 0, "del": 0, "updated": now})
            stats["debts"] += 1
        elif is_bank(it) and not quoted_code(it):
            rows["account"].append({"id": nid(), "entity": entity, "name": name, "emoji": "🏦", "currency": cur, "type": 2, "initial": amount, "sort": len(rows["account"]), "archived": 0, "wealth": 1, "del": 0, "updated": now})
            stats["accounts"] += 1
        else:
            aid, code, grp = nid(), quoted_code(it), group_for(it)
            if code:
                grp = "stocks" if code not in KNOWN_FIAT | KNOWN_CRYPTO | KNOWN_METAL else ("crypto" if code in KNOWN_CRYPTO else "metals" if code in KNOWN_METAL else "cash")
                rows["asset"].append({"id": aid, "entity": entity, "name": name, "emoji": EMOJI[grp], "cls": 1, "code": code, "qty": it.get("quantity") or 0, "currency": cur, "note": note, "sort": len(rows["asset"]), "archived": 0, "grp": grp, "del": 0, "updated": now})
            else:
                rows["asset"].append({"id": aid, "entity": entity, "name": name, "emoji": EMOJI[grp], "cls": 0, "code": None, "qty": 0, "currency": cur, "note": note, "sort": len(rows["asset"]), "archived": 0, "grp": grp, "del": 0, "updated": now})
                rows["asset_value"].append({"asset": aid, "day": today, "value": amount, "del": 0, "updated": now})
            stats["assets"] += 1
    for it in data.get("debt", []):
        val = it.get("value") or {}
        rows["debt"].append({"id": nid(), "dir": 1, "name": it.get("name") or "Debt", "user_id": 0, "amount": val.get("amount", 0) or 0, "currency": val.get("currency") or p.get("currency") or "USD",
                             "day": today, "due": 0, "note": notes(it), "entity": entity, "closed": 0, "del": 0, "updated": now})
        stats["debts"] += 1

env = {"fork": "telegram-fork-backup", "v": 3, "exportedAt": now, "device": "kubera-import",
       "habits": {"cats": [], "habits": [], "marks": [], "goals": []}, "finance": rows}
json.dump(env, open(dst, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(stats, "→", dst)
