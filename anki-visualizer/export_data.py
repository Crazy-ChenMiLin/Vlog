import sqlite3
import os
import glob
import json
import shutil
import tempfile
from datetime import datetime, date, timedelta

def find_anki_db():
    appdata = os.environ.get("APPDATA", "")
    anki_base = os.path.join(appdata, "Anki2")
    if not os.path.exists(anki_base):
        return None
    pattern = os.path.join(anki_base, "*", "collection.anki2")
    matches = glob.glob(pattern)
    if matches:
        matches.sort(key=lambda p: os.path.getmtime(p), reverse=True)
        return matches[0]
    return None

def extract_anki_data(db_path=None):
    if not db_path:
        db_path = find_anki_db()
    if not db_path or not os.path.exists(db_path):
        raise FileNotFoundError(f"未找到本地 Anki 数据库，路径：{db_path}")

    temp_dir = tempfile.mkdtemp()
    temp_db = os.path.join(temp_dir, "collection.anki2")
    shutil.copy2(db_path, temp_db)
    
    wal_path = db_path + "-wal"
    if os.path.exists(wal_path):
        try:
            shutil.copy2(wal_path, temp_db + "-wal")
        except Exception:
            pass

    conn = sqlite3.connect(f"file:{temp_db}?mode=ro", uri=True)
    conn.create_collation("unicase", lambda a, b: 0 if a == b else (1 if a > b else -1))
    c = conn.cursor()

    # 1. Collection creation time & day index calculation
    col = c.execute("SELECT crt, mod, dconf FROM col").fetchone()
    crt = col[0]
    crt_dt = datetime.fromtimestamp(crt)
    today = date.today()
    today_day_index = (today - crt_dt.date()).days

    # 2. Decks
    decks_map = {}
    try:
        for did, name in c.execute("SELECT id, name FROM decks").fetchall():
            clean_name = name.replace('\x1f', ' / ')
            decks_map[did] = clean_name
    except Exception as e:
        print("Warning reading decks:", e)

    # 3. Cards & Notes & Maturity & Due
    cards_list = []
    deck_card_counts = {did: {"new": 0, "learn": 0, "due": 0, "mature": 0, "young": 0, "total": 0, "reviews": 0} for did in decks_map}

    overall_cards = {
        "total": 0,
        "new": 0,
        "learn": 0,
        "due": 0,
        "mature": 0,
        "young": 0,
        "suspended": 0
    }

    try:
        rows = c.execute("""
            SELECT c.id, c.nid, c.did, c.ord, c.type, c.queue, c.due, c.ivl, c.factor, c.reps, c.lapses, c.left,
                   n.flds, n.tags
            FROM cards c
            JOIN notes n ON c.nid = n.id
            ORDER BY c.reps DESC
        """).fetchall()

        overall_cards["total"] = len(rows)

        for r in rows:
            cid, nid, did, cord, ctype, queue, due, ivl, factor, reps, lapses, cleft, flds, tags = r
            fld_parts = flds.split('\x1f')
            q = fld_parts[0] if len(fld_parts) > 0 else ""
            a = fld_parts[1] if len(fld_parts) > 1 else ""
            d_name = decks_map.get(did, "默认牌组")

            if did not in deck_card_counts:
                deck_card_counts[did] = {"new": 0, "learn": 0, "due": 0, "mature": 0, "young": 0, "total": 0, "reviews": 0}

            deck_card_counts[did]["total"] += 1

            # Card status
            status = "new"
            is_due_today = False
            if queue == 0:
                status = "new"
                overall_cards["new"] += 1
                deck_card_counts[did]["new"] += 1
            elif queue in (1, 3):
                status = "learn"
                is_due_today = True
                overall_cards["learn"] += 1
                deck_card_counts[did]["learn"] += 1
            elif queue == 2:
                # Review card
                if ivl >= 21:
                    status = "mature"
                    overall_cards["mature"] += 1
                    deck_card_counts[did]["mature"] += 1
                else:
                    status = "young"
                    overall_cards["young"] += 1
                    deck_card_counts[did]["young"] += 1

                if due <= today_day_index:
                    is_due_today = True
                    overall_cards["due"] += 1
                    deck_card_counts[did]["due"] += 1
            elif queue == -1:
                status = "suspended"
                overall_cards["suspended"] += 1

            cards_list.append({
                "id": cid,
                "nid": nid,
                "deckId": did,
                "deckName": d_name,
                "question": q,
                "answer": a,
                "status": status,
                "isDueToday": is_due_today,
                "type": ctype,
                "queue": queue,
                "due": due,
                "ivl": ivl,
                "factor": round(factor / 10.0, 1) if factor else 250.0,
                "reps": reps,
                "lapses": lapses,
                "tags": [t for t in tags.split() if t]
            })
    except Exception as e:
        print("Warning reading cards/notes:", e)

    # 4. Revlog analysis
    daily_stats = {}
    hourly_stats = [{"hour": h, "count": 0, "time_seconds": 0} for h in range(24)]
    ease_counts = {"again": 0, "hard": 0, "good": 0, "easy": 0}
    type_counts = {"learn": 0, "review": 0, "relearn": 0, "cram": 0}
    total_study_time_ms = 0
    total_reviews = 0
    monthly_stats = {}

    card_to_deck = {card["id"]: card["deckId"] for card in cards_list}

    try:
        rev_rows = c.execute("SELECT id, cid, usn, ease, ivl, lastIvl, factor, time, type FROM revlog ORDER BY id ASC").fetchall()
        total_reviews = len(rev_rows)

        for r in rev_rows:
            rid, cid, usn, ease, ivl, last_ivl, factor, study_time, rev_type = r
            ts = rid / 1000.0
            dt = datetime.fromtimestamp(ts)
            date_str = dt.strftime("%Y-%m-%d")
            month_str = dt.strftime("%Y-%m")
            h = dt.hour

            total_study_time_ms += study_time

            hourly_stats[h]["count"] += 1
            hourly_stats[h]["time_seconds"] += round(study_time / 1000.0, 1)

            if ease == 1: ease_counts["again"] += 1
            elif ease == 2: ease_counts["hard"] += 1
            elif ease == 3: ease_counts["good"] += 1
            elif ease == 4: ease_counts["easy"] += 1

            if rev_type == 0: type_counts["learn"] += 1
            elif rev_type == 1: type_counts["review"] += 1
            elif rev_type == 2: type_counts["relearn"] += 1
            elif rev_type == 3: type_counts["cram"] += 1

            d_id = card_to_deck.get(cid)
            if d_id and d_id in deck_card_counts:
                deck_card_counts[d_id]["reviews"] += 1

            if date_str not in daily_stats:
                daily_stats[date_str] = {
                    "date": date_str,
                    "count": 0,
                    "time_seconds": 0,
                    "again": 0,
                    "hard": 0,
                    "good": 0,
                    "easy": 0
                }
            daily_stats[date_str]["count"] += 1
            daily_stats[date_str]["time_seconds"] += round(study_time / 1000.0, 1)
            if ease == 1: daily_stats[date_str]["again"] += 1
            elif ease == 2: daily_stats[date_str]["hard"] += 1
            elif ease == 3: daily_stats[date_str]["good"] += 1
            elif ease == 4: daily_stats[date_str]["easy"] += 1

            if month_str not in monthly_stats:
                monthly_stats[month_str] = {
                    "month": month_str,
                    "reviews": 0,
                    "time_minutes": 0
                }
            monthly_stats[month_str]["reviews"] += 1
            monthly_stats[month_str]["time_minutes"] += round(study_time / 60000.0, 1)

    except Exception as e:
        print("Warning reading revlog:", e)

    # 5. Graves (Archived / Historical deleted cards)
    graves_cnt = 0
    try:
        graves_cnt = c.execute("SELECT count(*) FROM graves").fetchone()[0]
    except Exception:
        pass

    notes_cnt = len(cards_list)
    try:
        notes_cnt = c.execute("SELECT count(*) FROM notes").fetchone()[0]
    except Exception:
        pass

    conn.close()

    try:
        shutil.rmtree(temp_dir)
    except Exception:
        pass

    # 6. Streaks Calculation
    sorted_dates = sorted(daily_stats.keys())
    cur_streak = 0
    max_streak = 0
    prev_dt = None

    for d_str in sorted_dates:
        curr_dt = datetime.strptime(d_str, "%Y-%m-%d").date()
        if prev_dt is None:
            cur_streak = 1
        else:
            diff = (curr_dt - prev_dt).days
            if diff == 1:
                cur_streak += 1
            else:
                cur_streak = 1
        if cur_streak > max_streak:
            max_streak = cur_streak
        prev_dt = curr_dt

    today_str = today.strftime("%Y-%m-%d")
    yesterday_str = (today - timedelta(days=1)).strftime("%Y-%m-%d")

    last_active_date = datetime.strptime(sorted_dates[-1], "%Y-%m-%d").date() if sorted_dates else today
    days_since_last = (today - last_active_date).days

    # Active streak
    active_streak = cur_streak if days_since_last <= 1 else 0

    # Today's status
    today_record = daily_stats.get(today_str, {
        "count": 0, "time_seconds": 0, "again": 0, "hard": 0, "good": 0, "easy": 0
    })
    is_today_done = today_record["count"] > 0

    yesterday_record = daily_stats.get(yesterday_str, {
        "count": 0, "time_seconds": 0, "again": 0, "hard": 0, "good": 0, "easy": 0
    })

    # Retention rate
    successful_reviews = ease_counts["good"] + ease_counts["easy"] + ease_counts["hard"]
    retention_rate = round(successful_reviews / total_reviews * 100, 1) if total_reviews > 0 else 0.0

    # Decks summary table with Anki columns
    decks_summary = []
    for did, name in decks_map.items():
        st = deck_card_counts.get(did, {"new": 0, "learn": 0, "due": 0, "mature": 0, "young": 0, "total": 0, "reviews": 0})
        # Only show decks that have cards or reviews
        if st["total"] > 0 or st["reviews"] > 0:
            decks_summary.append({
                "id": did,
                "name": name,
                "cleanName": name.replace("设计模式 / ", "") if name != "设计模式" else name,
                "new": st["new"],
                "learn": st["learn"],
                "due": st["due"],
                "mature": st["mature"],
                "young": st["young"],
                "total": st["total"],
                "reviews": st["reviews"]
            })

    output_data = {
        "meta": {
            "exportedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "todayDate": today_str,
            "dbPath": db_path,
            "profileName": os.path.basename(os.path.dirname(db_path)),
            "firstDate": sorted_dates[0] if sorted_dates else "",
            "lastDate": sorted_dates[-1] if sorted_dates else "",
            "activeDays": len(sorted_dates)
        },
        # Focus 1: Today's status & persistence (今天做没做、做了多少多久、坚持了多久)
        "today": {
            "date": today_str,
            "isDone": is_today_done,
            "reviews": today_record["count"],
            "timeMinutes": round(today_record["time_seconds"] / 60.0, 1),
            "dueRemaining": overall_cards["due"] + overall_cards["learn"],
            "ease": {
                "again": today_record["again"],
                "hard": today_record["hard"],
                "good": today_record["good"],
                "easy": today_record["easy"]
            },
            "yesterday": {
                "reviews": yesterday_record["count"],
                "timeMinutes": round(yesterday_record["time_seconds"] / 60.0, 1)
            }
        },
        "streak": {
            "current": active_streak,
            "max": max_streak,
            "totalDays": len(sorted_dates),
            "lastActive": sorted_dates[-1] if sorted_dates else "",
            "daysSinceLast": days_since_last
        },
        # Focus 2: Total cards accumulated & maturity (累计了多少卡片)
        "cardAssets": {
            "totalActive": overall_cards["total"],
            "historicalArchived": graves_cnt,
            "lifetimeAccumulated": overall_cards["total"] + graves_cnt,
            "mature": overall_cards["mature"],    # >= 21 days
            "young": overall_cards["young"],      # < 21 days
            "learning": overall_cards["learn"],
            "new": overall_cards["new"],
            "suspended": overall_cards["suspended"],
            "totalNotes": notes_cnt,
            "dueToday": overall_cards["due"]
        },
        # Overall momentum
        "kpis": {
            "totalReviews": total_reviews,
            "totalStudyTimeMinutes": round(total_study_time_ms / 60000.0, 1),
            "totalStudyTimeHours": round(total_study_time_ms / 3600000.0, 2),
            "avgTimePerCardSeconds": round((total_study_time_ms / total_reviews) / 1000.0, 1) if total_reviews > 0 else 0.0,
            "retentionRate": retention_rate
        },
        "decks": decks_summary,
        "dailyStats": [daily_stats[k] for k in sorted_dates],
        "monthlyStats": [monthly_stats[k] for k in sorted(monthly_stats.keys())],
        "hourlyStats": hourly_stats,
        "easeDistribution": ease_counts,
        "cards": cards_list
    }

    return output_data

def save_exports():
    data = extract_anki_data()
    curr_dir = os.path.dirname(os.path.abspath(__file__))
    json_path = os.path.join(curr_dir, "anki_data.json")
    js_path = os.path.join(curr_dir, "data.js")

    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    with open(js_path, "w", encoding="utf-8") as f:
        f.write("window.ANKI_INITIAL_DATA = " + json.dumps(data, ensure_ascii=False) + ";\n")

    print(f"数据导出成功！")
    print(f"JSON: {json_path}")
    print(f"JS:   {js_path}")
    print(f"今日完成状态: {'已打卡' if data['today']['isDone'] else '待完成'}, 今日复习: {data['today']['reviews']}张, 连续连胜: {data['streak']['current']}天, 累计卡片: {data['cardAssets']['totalActive']}张(历史累计{data['cardAssets']['lifetimeAccumulated']}张)")
    return data

if __name__ == "__main__":
    save_exports()
