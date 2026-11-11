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

    def items(results, tv):
        out = []
        for r in results:
            if len(out) >= 4:
                break
            backdrop = r.get("backdrop_path")
            title = (r.get("name") if tv else r.get("title")) or ""
            if not backdrop or not title.strip():
                continue
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
            out.append({
                "title": title.strip(),
                "meta": "  ·  ".join(parts),
                "desc": (r.get("overview") or "").strip(),
                "images": ["https://image.tmdb.org/t/p/w1280" + backdrop],
            })
        return out

    sections = {}
    for key, name, pid in wanted:
        common = dict(language="de-DE", watch_region="DE", with_watch_providers=pid,
                      with_watch_monetization_types="flatrate", sort_by="popularity.desc")
        tv = items(tmdb("/discover/tv", **dict(common, **{"air_date.gte": recent})).get("results", []), True)
        mv = items(tmdb("/discover/movie", **dict(common, **{"primary_release_date.gte": year})).get("results", []), False)
        mixed = []
        for i in range(max(len(tv), len(mv))):
            if i < len(tv):
                mixed.append(tv[i])
            if i < len(mv):
                mixed.append(mv[i])
        if mixed:
            sections[key] = {
                "label": "Neu und beliebt bei " + name,
                "source": "Daten: TMDB · JustWatch",
                "items": mixed[:6],
            }
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
    if len(streams) < 5:
        seen = set(s.get("user_login") for s in streams)
        for s in http("https://api.twitch.tv/helix/streams?first=10&language=de", headers).get("data", []):
            if s.get("user_login") not in seen and len(streams) < 6:
                streams.append(s)
    stamp = datetime.datetime.utcnow().strftime("%Y%m%d%H%M")
    out = []
    for s in streams[:6]:
        thumb = (s.get("thumbnail_url") or "").replace("{width}", "1280").replace("{height}", "720")
        viewers = "{:,}".format(s.get("viewer_count", 0)).replace(",", ".")
        meta = ["Live"]
        if s.get("game_name"):
            meta.append(s["game_name"])
        meta.append(viewers + " Zuschauer")
        out.append({
            "title": s.get("user_name") or s.get("user_login") or "",
            "meta": "  ·  ".join(meta),
            "desc": (s.get("title") or "").strip(),
            "images": [thumb + "?t=" + stamp] if thumb else [],
            "link": "https://www.twitch.tv/" + (s.get("user_login") or ""),
        })
    return {"label": "Jetzt live", "source": "Quelle: Twitch", "items": out}


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "content.json"
    with open(os.path.join(HERE, "config.json"), encoding="utf-8") as f:
        config = json.load(f)
    out = {"updated": datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"), "status": {}}

    if os.environ.get("TMDB_KEY", "").strip():
        try:
            sections = tmdb_sections()
            out.update(sections)
            out["status"]["tmdb"] = "ok (%d Anbieter)" % len(sections)
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

    os.makedirs(os.path.dirname(os.path.abspath(target)), exist_ok=True)
    with open(target, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(json.dumps(out["status"], ensure_ascii=False))


if __name__ == "__main__":
    main()
