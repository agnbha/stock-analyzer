"""Read-only. Does a look at the first 30 minutes tell you anything about the rest of the day?

For every stock-session in the stored minute history (62 symbols):
  gap      = 09:15 open / previous session's last close - 1
  OR       = high/low of the first 15 minutes (09:15-09:29)
  at 09:45 = last price (close of the 09:44 candle) and VWAP so far
  after    = 15:29 close / 09:45 price - 1   (what is still on the table after the look)
  dip      = lowest low after 09:45 / 09:45 price - 1  (what you must survive to get it)
Returns are shown minus that day's all-stock average, so a rising or falling market is not credited to the rule.
"""
import pathlib, sys

# Read-only: the database is opened with mode=ro. Pass another path as the first argument to use a copy.
DB = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[2] / "data" / "stock-analyzer.db"
URI = "file:" + str(DB) + "?mode=ro"
import math, sqlite3, statistics as st
from collections import defaultdict

con = sqlite3.connect(URI, uri=True)
sym = dict(con.execute("select id, symbol from instrument"))
OFF = 19800  # IST

days = defaultdict(list)  # (iid, date) -> [(minute_of_day, o, h, l, c, v)]
for iid, ts, o, h, l, c, v in con.execute(
        "select instrument_id, ts_epoch, open, high, low, close, volume from candle "
        "where interval_minutes=1 and close>0 order by instrument_id, ts_epoch"):
    t = ts + OFF
    days[(iid, t // 86400)].append(((t % 86400) // 60, o, h, l, c, v or 0))

rows, prev_close = [], {}
for (iid, dn), cs in sorted(days.items()):
    if len(cs) < 300 or cs[0][0] != 555:
        prev_close[iid] = cs[-1][4] if cs else prev_close.get(iid)
        continue
    pc = prev_close.get(iid)
    prev_close[iid] = cs[-1][4]
    first = [x for x in cs if x[0] < 570]
    look = [x for x in cs if x[0] <= 584]
    rest = [x for x in cs if x[0] > 584]
    if len(first) < 10 or len(look) < 25 or len(rest) < 200 or pc is None:
        continue
    price = look[-1][4]
    pv = sum((x[2] + x[3] + x[4]) / 3 * x[5] for x in look)
    vol = sum(x[5] for x in look)
    vwap = pv / vol if vol else price
    orh, orl = max(x[2] for x in first), min(x[3] for x in first)
    rows.append(dict(sym=sym[iid], d=dn, gap=(cs[0][1] / pc - 1) * 100, price=price, vwap=vwap, orh=orh, orl=orl,
                     after=(cs[-1][4] / price - 1) * 100, dip=(min(x[3] for x in rest) / price - 1) * 100,
                     fromopen=(price / cs[0][1] - 1) * 100))

by_day = defaultdict(list)
for r in rows:
    by_day[r["d"]].append(r)
for d, rs in by_day.items():
    m = st.mean(r["after"] for r in rs); md = st.mean(r["dip"] for r in rs)
    for r in rs:
        r["after_x"], r["dip_x"] = r["after"] - m, r["dip"] - md
        r["state"] = ("above OR high & VWAP" if r["price"] > r["orh"] and r["price"] > r["vwap"]
                      else "below OR low" if r["price"] < r["orl"]
                      else "inside OR, above VWAP" if r["price"] > r["vwap"] else "inside OR, below VWAP")

print(f"stock-sessions: {len(rows):,} over {len(by_day)} days, {len(set(r['sym'] for r in rows))} symbols")


def show(label, rs):
    if len(rs) < 20:
        print(f"  {label:<46} n={len(rs)} (too few)"); return
    a = [r["after_x"] for r in rs]
    # s.e. from per-day means to respect same-day correlation
    perday = defaultdict(list)
    for r in rs: perday[r["d"]].append(r["after_x"])
    dm = [st.mean(v) for v in perday.values()]
    se = st.pstdev(dm) / math.sqrt(len(dm)) if len(dm) > 1 else float("nan")
    print(f"  {label:<46} n={len(rs):>5}  after-09:45 {st.mean(a):+6.2f} +/-{se:4.2f}   "
          f"worst dip {st.mean(r['dip'] for r in rs):+5.2f}   share ending up {100*sum(1 for r in rs if r['after']>0)/len(rs):4.1f}%")


print("\nA) All stock-sessions, by state at 09:45 (return minus that day's average, %-points):")
for s in ("above OR high & VWAP", "inside OR, above VWAP", "inside OR, below VWAP", "below OR low"):
    show(s, [r for r in rows if r["state"] == s])

print("\nB) By gap at the open:")
show("gap up > 2%", [r for r in rows if r["gap"] > 2])
show("gap up 0.5% .. 2%", [r for r in rows if 0.5 < r["gap"] <= 2])
show("flat (-0.5% .. 0.5%)", [r for r in rows if -0.5 <= r["gap"] <= 0.5])
show("gap down -2% .. -0.5%", [r for r in rows if -2 <= r["gap"] < -0.5])
show("gap down < -2%", [r for r in rows if r["gap"] < -2])

print("\nC) Gap up > 0.5% AND state at 09:45 (the 'gap-and-fade' question):")
for s in ("above OR high & VWAP", "inside OR, above VWAP", "inside OR, below VWAP", "below OR low"):
    show(s, [r for r in rows if r["gap"] > 0.5 and r["state"] == s])

# the shortlist: top 5 by the Watchlist's own score the evening before
tr = defaultdict(list)
for s_, d, c, sma, rsi in con.execute("select symbol, session_date, close, sma20, rsi14 from v_symbol_trend order by symbol, session_date"):
    tr[s_].append((d, c, sma, rsi))
score = defaultdict(dict)  # date -> sym -> score
for s_, ser in tr.items():
    cl = [x[1] for x in ser]
    for k in range(5, len(ser)):
        d, c, sma, rsi = ser[k]
        if sma and rsi is not None:
            score[d][s_] = (c / sma - 1) * 100 + (c / cl[k - 5] - 1) * 100 + (rsi - 50) / 10 - max(0, rsi - 75) / 5
import datetime
dn_of = lambda ds: (datetime.date.fromisoformat(ds) - datetime.date(1970, 1, 1)).days
dates = sorted(score)
nxt = {dates[i]: dates[i + 1] for i in range(len(dates) - 1)}
pick = set()
for d, sc in score.items():
    if d in nxt and len(sc) >= 30:
        for s_ in sorted(sc, key=sc.get, reverse=True)[:5]:
            pick.add((s_, dn_of(nxt[d])))
picks = [r for r in rows if (r["sym"], r["d"]) in pick]
print(f"\nD) The Watchlist's top-5 (by the evening-before score), n={len(picks)} stock-sessions:")
show("all top-5 picks", picks)
for s in ("above OR high & VWAP", "inside OR, above VWAP", "inside OR, below VWAP", "below OR low"):
    show(s, [r for r in picks if r["state"] == s])
