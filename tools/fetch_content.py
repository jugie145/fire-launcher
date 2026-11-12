#!/usr/bin/env python3
"""Holt die Inhalte fuer die grosse Flaeche des Launchers und schreibt sie als content.json.

Laeuft in GitHub Actions. Die Schluessel kommen aus den Repository-Secrets und landen
weder in der App noch in der erzeugten Datei:
  TMDB_KEY                   Filme und Serien (Netflix, Prime Video, WOW, Disney+)
  YT_KEY                     YouTube-Trends in Deutschland
  TWITCH_ID, TWITCH_SECRET   Live-Streams auf Twitch
Fehlt ein Schluessel, wird der Abschnitt einfach weggelassen.
"""
import datetime
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def http(url, headers=None, data=None):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read().decode("utf-8"))


def problem(e):
    """Fehlertext ohne Adresse, damit nie ein Schluessel in der Datei landet."""
    if isinstance(e, urllib.error.HTTPError):
        return "Fehler: HTTP %s" % e.code
    return "Fehler: %s" % type(e).__name__


# ---------------- Filme und Serien (TMDB, Anbieter-Daten von JustWatch) ----------------

def tmdb(path, **params):
    key = os.environ.get("TMDB_KEY", "").strip()
    headers = {"Accept": "application/json"}
    if len(key) > 60:
        headers["Authorization"] = "Bearer " + key
    else:
        params["api_key"] = key
    return http("https://api.themoviedb.org/3" + path + "?" + urllib.parse.urlencode(params), headers)


def trailer_for(kind, tid):
    """Offizieller YouTube-Trailer (deutsch bevorzugt), sonst leer."""
    try:
        videos = tmdb("/%s/%s/videos" % (kind, tid), language="de-DE").get("results", [])
        if not videos:
            videos = tmdb("/%s/%s/videos" % (kind, tid), language="en-US").get("results", [])
        cands = [v for v in videos if v.get("site") == "YouTube" and v.get("type") == "Trailer" and v.get("key")]
        cands.sort(key=lambda v: (not v.get("official", False), v.get("iso_639_1") not in ("de", "en")))
        return "https://www.youtube.com/watch?v=" + cands[0]["key"] if cands else ""
    except Exception:  # noqa: BLE001
        return ""


MONTHS = ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August",
          "September", "Oktober", "November", "Dezember"]

# Genre des Tages (TMDB-Film-Genres), wechselt taeglich
DAY_GENRES = [(28, "Action"), (35, "Komödie"), (53, "Thriller"), (878, "Science Fiction"),
              (27, "Horror"), (16, "Animation"), (80, "Krimi"), (12, "Abenteuer"), (10749, "Liebesfilm")]


def tmdb_sections():
    genres = {}
    for kind in ("tv", "movie"):
        for g in tmdb("/genre/%s/list" % kind, language="de").get("genres", []):
            genres[g["id"]] = g["name"]
    names = {}
    for p in tmdb("/watch/providers/tv", watch_region="DE", language="de-DE").get("results", []):
        names[p.get("provider_name", "").strip().lower()] = p.get("provider_id")
    wanted = [
        ("netflix", "Netflix", names.get("netflix", 8)),
        ("prime", "Prime Video", names.get("amazon prime video", 9)),
        ("wow", "WOW", names.get("wow", 30)),
        ("disney", "Disney+", names.get("disney plus", 337)),
    ]
    today = datetime.date.today()
    recent = (today - datetime.timedelta(days=75)).isoformat()
    year = (today - datetime.timedelta(days=365)).isoformat()
    # Serien mit neuen Folgen, aber keine Dauerbrenner, die seit Jahrzehnten laufen
    young = (today - datetime.timedelta(days=5 * 365)).isoformat()
    gid, gname = DAY_GENRES[today.toordinal() % len(DAY_GENRES)]

    def items(results, tv, label, limit, used, trailers=0):
        out = []
        for r in results:
            if len(out) >= limit:
                break
            backdrop = r.get("backdrop_path")
            title = ((r.get("name") if tv else r.get("title")) or "").strip()
            if not backdrop or not title or title.lower() in used:
                continue
            used.add(title.lower())
            date = (r.get("first_air_date") if tv else r.get("release_date")) or ""
            parts = []
            if len(date) >= 4:
                parts.append(date[:4])
            parts.append("Serie" if tv else "Film")
            gs = [genres[g] for g in (r.get("genre_ids") or [])[:2] if g in genres]
            if gs:
                parts.append(", ".join(gs))
            vote = r.get("vote_average") or 0
            if vote > 0.1:
                parts.append("★ " + ("%.1f" % vote).replace(".", ","))
            poster_path = r.get("poster_path") or ""
            out.append({
                "title": title,
                "meta": "  ·  ".join(parts),
                "desc": (r.get("overview") or "").strip(),
                "label": label,
                "images": ["https://image.tmdb.org/t/p/w1280" + backdrop],
                "poster": "https://image.tmdb.org/t/p/w500" + poster_path if poster_path else "",
                "trailer": trailer_for("tv" if tv else "movie", r.get("id")) if len(out) < trailers else "",
            })
        return out

    def discover(kind, **extra):
        try:
            return tmdb("/discover/" + kind, **extra).get("results", [])
        except Exception:  # noqa: BLE001
            return []

    sections = {}
    for key, name, pid in wanted:
        common = dict(language="de-DE", watch_region="DE", with_watch_providers=pid,
                      with_watch_monetization_types="flatrate")
        used = set()
        groups = [
            items(discover("tv", sort_by="popularity.desc", **dict(common, **{"air_date.gte": recent, "first_air_date.gte": young})),
                  True, "Neue Folgen bei " + name, 6, used, trailers=3),
            items(discover("movie", sort_by="popularity.desc", **dict(common, **{"primary_release_date.gte": year})),
                  False, "Neue Filme bei " + name, 6, used, trailers=3),
            items(discover("movie", sort_by="popularity.desc", **common), False, "Gerade angesagt", 5, used),
            items(discover("tv", sort_by="popularity.desc", **common), True, "Beliebte Serien", 5, used),
            items(discover("movie", sort_by="vote_average.desc", **dict(common, **{"vote_count.gte": 1500})),
                  False, "Bestbewertete Filme", 5, used),
            items(discover("tv", sort_by="vote_average.desc", **dict(common, **{"vote_count.gte": 500})),
                  True, "Bestbewertete Serien", 4, used),
            items(discover("movie", sort_by="popularity.desc", with_genres=gid, **common),
                  False, "Heute empfohlen: " + gname, 5, used),
        ]
        # reihum aus allen Gruppen, damit es abwechslungsreich bleibt
        mixed = []
        for i in range(max(len(g) for g in groups)):
            for g in groups:
                if i < len(g):
                    mixed.append(g[i])
        if mixed:
            sections[key] = {
                "label": name,
                "source": "Daten: TMDB · JustWatch",
                "items": mixed,
            }

    # Kino: was gerade laeuft und was demnaechst startet
    kino = []
    used = set()
    try:
        now_playing = tmdb("/movie/now_playing", language="de-DE", region="DE").get("results", [])
        kino += items(now_playing, False, "Jetzt im Kino", 10, used, trailers=4)
    except Exception:  # noqa: BLE001
        pass
    try:
        upcoming = [r for r in tmdb("/movie/upcoming", language="de-DE", region="DE").get("results", [])
                    if (r.get("release_date") or "") > today.isoformat()]
        soon = items(upcoming, False, "Bald im Kino", 8, used, trailers=3)
        # Kinostart in die Infozeile schreiben
        dates = {(r.get("title") or "").strip(): r.get("release_date") or "" for r in upcoming}
        for it in soon:
            d = dates.get(it["title"], "")
            if len(d) == 10:
                it["meta"] = "Kinostart %d. %s  ·  " % (int(d[8:]), MONTHS[int(d[5:7]) - 1]) + it["meta"]
        kino += soon
    except Exception:  # noqa: BLE001
        pass
    if kino:
        sections["kino"] = {"label": "Kino", "source": "Daten: TMDB", "items": kino}
    return sections


# ---------------- YouTube-Trends ----------------

def youtube_trends():
    key = os.environ.get("YT_KEY", "").strip()
    q = urllib.parse.urlencode(dict(part="snippet", chart="mostPopular", regionCode="DE", maxResults=12, key=key))
    data = http("https://www.googleapis.com/youtube/v3/videos?" + q)
    out = []
    for v in data.get("items", []):
        sn = v.get("snippet", {})
        vid = v.get("id")
        if not vid or not sn.get("title"):
            continue
        out.append({
            "title": sn["title"],
            "meta": sn.get("channelTitle", ""),
            "desc": "",
            "label": "Trends in Deutschland",
            "images": [
                "https://i.ytimg.com/vi/%s/maxresdefault.jpg" % vid,
                "https://i.ytimg.com/vi/%s/sddefault.jpg" % vid,
                "https://i.ytimg.com/vi/%s/hqdefault.jpg" % vid,
            ],
            "link": "https://www.youtube.com/watch?v=" + vid,
        })
    return {"label": "Trends in Deutschland", "source": "Quelle: YouTube", "items": out}


# ---------------- Twitch ----------------

def twitch(logins):
    cid = os.environ.get("TWITCH_ID", "").strip()
    secret = os.environ.get("TWITCH_SECRET", "").strip()
    body = urllib.parse.urlencode(dict(client_id=cid, client_secret=secret, grant_type="client_credentials")).encode()
    token = http("https://id.twitch.tv/oauth2/token", data=body)["access_token"]
    headers = {"Client-Id": cid, "Authorization": "Bearer " + token}
    streams = []
    if logins:
        q = "&".join("user_login=" + urllib.parse.quote(x) for x in logins[:100])
        streams += http("https://api.twitch.tv/helix/streams?first=20&" + q, headers).get("data", [])
    if len(streams) < 10:
        seen = set(s.get("user_login") for s in streams)
        for s in http("https://api.twitch.tv/helix/streams?first=20&language=de", headers).get("data", []):
            if s.get("user_login") not in seen and len(streams) < 10:
                streams.append(s)
    stamp = datetime.datetime.utcnow().strftime("%Y%m%d%H%M")
    out = []
    for s in streams[:10]:
        thumb = (s.get("thumbnail_url") or "").replace("{width}", "1280").replace("{height}", "720")
        viewers = "{:,}".format(s.get("viewer_count", 0)).replace(",", ".")
        meta = ["LIVE"]
        if s.get("game_name"):
            meta.append(s["game_name"])
        meta.append(viewers + " Zuschauer")
        mine = (s.get("user_login") or "").lower() in logins
        out.append({
            "label": "Deine Streamer sind live" if mine else "Gerade beliebt auf Twitch",
            "title": s.get("user_name") or s.get("user_login") or "",
            "meta": "  ·  ".join(meta),
            "desc": (s.get("title") or "").strip(),
            "images": [thumb + "?t=" + stamp] if thumb else [],
            "link": "https://www.twitch.tv/" + (s.get("user_login") or ""),
        })
    return {"label": "Jetzt live", "source": "Quelle: Twitch", "items": out}


# ---------------- YouTube-Kanaele: Namen in Kanal-IDs aufloesen (ohne Schluessel) ----------------

def youtube_channels(names):
    """Sucht jeden Namen auf YouTube (Filter: Kanaele) und nimmt den ersten Treffer."""
    import re
    out = []
    for name in names:
        if isinstance(name, dict):
            # fest eingetragener Kanal
            out.append({"query": name.get("name", ""), "id": name.get("id", ""), "name": name.get("name", "")})
            continue
        try:
            if name.startswith("@"):
                url = "https://www.youtube.com/" + urllib.parse.quote(name) + "?hl=de&gl=DE"
            else:
                url = "https://www.youtube.com/results?" + urllib.parse.urlencode({"search_query": name, "sp": "EgIQAg==", "hl": "de", "gl": "DE"})
            req = urllib.request.Request(url, headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36",
                "Accept-Language": "de-DE,de;q=0.9",
                "Cookie": "CONSENT=YES+1; SOCS=CAI"})
            with urllib.request.urlopen(req, timeout=25) as r:
                html = r.read().decode("utf-8", "replace")
            if name.startswith("@"):
                cid = re.search(r'<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[\w-]{22})"', html) \
                    or re.search(r'"externalId":"(UC[\w-]{22})"', html)
                title = re.search(r'<meta property="og:title" content="([^"]*)"', html)
                if cid:
                    out.append({"query": name, "id": cid.group(1), "name": title.group(1) if title else name[1:]})
                continue
            m = re.search(r'"channelRenderer":\{"channelId":"(UC[\w-]{22})","title":\{"simpleText":"([^"]*)"', html)
            if m:
                out.append({"query": name, "id": m.group(1), "name": m.group(2)})
        except Exception:  # noqa: BLE001
            continue
    return out


# ---------------- HSV (OpenLigaDB, ohne Schluessel) ----------------

def hsv():
    now = datetime.datetime.utcnow()
    season = now.year if now.month >= 7 else now.year - 1
    games = []
    for league in ("bl1", "bl2", "dfb"):
        try:
            data = http("https://api.openligadb.de/getmatchdata/%s/%d" % (league, season))
        except Exception:  # noqa: BLE001
            continue
        for m in data or []:
            t1 = ((m.get("team1") or {}).get("teamName") or "")
            t2 = ((m.get("team2") or {}).get("teamName") or "")
            home = "hamburger sv" in t1.lower()
            if not home and "hamburger sv" not in t2.lower():
                continue
            utc = (m.get("matchDateTimeUTC") or "")[:19]
            try:
                kick = datetime.datetime.strptime(utc, "%Y-%m-%dT%H:%M:%S")
            except ValueError:
                continue
            if kick < now - datetime.timedelta(hours=3):
                continue
            games.append({"utc": utc + "Z", "opp": t2 if home else t1, "home": home,
                          "league": m.get("leagueName") or league})
    games.sort(key=lambda g: g["utc"])
    return games[:4]


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "content.json"
    with open(os.path.join(HERE, "config.json"), encoding="utf-8") as f:
        config = json.load(f)
    out = {"updated": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"), "status": {}}

    if os.environ.get("TMDB_KEY", "").strip():
        try:
            sections = tmdb_sections()
            out.update(sections)
            out["status"]["tmdb"] = "ok (" + ", ".join("%s %d" % (k, len(v["items"])) for k, v in sections.items()) + ")"
        except Exception as e:  # noqa: BLE001
            out["status"]["tmdb"] = problem(e)
    else:
        out["status"]["tmdb"] = "kein Schluessel"

    if os.environ.get("YT_KEY", "").strip():
        try:
            out["youtube_trends"] = youtube_trends()
            out["status"]["youtube"] = "ok (%d Videos)" % len(out["youtube_trends"]["items"])
        except Exception as e:  # noqa: BLE001
            out["status"]["youtube"] = problem(e)
    else:
        out["status"]["youtube"] = "kein Schluessel"

    if os.environ.get("TWITCH_ID", "").strip() and os.environ.get("TWITCH_SECRET", "").strip():
        try:
            out["twitch"] = twitch([x.lower() for x in config.get("twitch", [])])
            out["status"]["twitch"] = "ok (%d Streams)" % len(out["twitch"]["items"])
        except Exception as e:  # noqa: BLE001
            out["status"]["twitch"] = problem(e)
    else:
        out["status"]["twitch"] = "kein Schluessel"

    try:
        out["youtube_channels"] = youtube_channels(config.get("youtube", []))
        out["status"]["kanaele"] = "ok (%d von %d)" % (len(out["youtube_channels"]), len(config.get("youtube", [])))
    except Exception as e:  # noqa: BLE001
        out["status"]["kanaele"] = problem(e)

    try:
        out["hsv"] = hsv()
        out["status"]["hsv"] = "ok (%d Spiele)" % len(out["hsv"])
    except Exception as e:  # noqa: BLE001
        out["status"]["hsv"] = problem(e)

    os.makedirs(os.path.dirname(os.path.abspath(target)), exist_ok=True)
    with open(target, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps(out["status"], ensure_ascii=False))


if __name__ == "__main__":
    main()
