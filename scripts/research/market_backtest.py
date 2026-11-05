"""Read-only, whole market: does a Watchlist-style score predict the next session, and which extra inputs add to it?

daily_bar: 2,825 instruments, 2025-09-22..2026-11-04. Liquid names only (20-day median turnover >= Rs 10 crore).
Every number is the mean over sessions of that session's cross-sectional mean, so one busy day cannot dominate.
"""
import pathlib, sys

# Read-only: the database is opened with mode=ro. Pass another path as the first argument to use a copy.
DB = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[2] / "data" / "stock-analyzer.db"
URI = "file:" + str(DB) + "?mode=ro"
import math, sqlite3, statistics as st
from collections import defaultdict

con = sqlite3.connect(URI, uri=True)
bars = defaultdict(list)
for iid, d, o, h, l, c, v in con.execute(
        "SELECT instrument_id, session_date, open, high, low, close, volume FROM daily_bar "
        "WHERE close > 0 AND open > 0 ORDER BY instrument_id, session_date"):
    bars[iid].append((d, o, h, l, c, v))

by_date = defaultdict(list)
for iid, s in bars.items():
    if len(s) < 40:
        continue
    closes = [x[4] for x in s]
    gain = loss = None
    rsi = [None] * len(s)
    for k in range(1, len(s)):  # Wilder RSI(14)
        ch = closes[k] - closes[k - 1]
        g, ls = max(ch, 0), max(-ch, 0)
        if k < 14:
            gain = (gain or 0) + g / 14; loss = (loss or 0) + ls / 14
        elif k == 14:
            gain = (gain or 0) + g / 14; loss = (loss or 0) + ls / 14
            rsi[k] = 100 if loss == 0 else 100 - 100 / (1 + gain / loss)
        else:
            gain = (gain * 13 + g) / 14; loss = (loss * 13 + ls) / 14
            rsi[k] = 100 if loss == 0 else 100 - 100 / (1 + gain / loss)
    for k in range(25, len(s) - 1):
        d, o, h, l, c, v = s[k]
        window = s[k - 19:k + 1]
        sma20 = sum(x[4] for x in window) / 20
        turnover = st.median(x[4] * x[5] for x in window)
        if turnover < 1e8 or rsi[k] is None:
            continue
        ret5 = (c / closes[k - 5] - 1) * 100
        pct = (c / sma20 - 1) * 100
        avgvol = sum(x[5] for x in s[k - 19:k]) / 19
        nd, no, nh, nl, nc, nv = s[k + 1]
        by_date[d].append(dict(
            score=pct + ret5 + (rsi[k] - 50) / 10 - max(0, rsi[k] - 75) / 5,
            ret5=ret5, pct=pct, rsi=rsi[k], relvol=(v / avgvol if avgvol else None),
            gap=(no / c - 1) * 100, cc=(nc / c - 1) * 100, oc=(nc / no - 1) * 100,
            dd=(nl / no - 1) * 100,
            close_pos=((c - l) / (h - l) if h > l else 0.5)))

dates = sorted(d for d in by_date if len(by_date[d]) >= 150)
print(f"sessions: {len(dates)} ({dates[0]}..{dates[-1]}), liquid stock-sessions: {sum(len(by_date[d]) for d in dates):,}, "
      f"typical names per session: {int(st.median(len(by_date[d]) for d in dates))}")


def per_day(pick, field):
    """mean over sessions of (mean of `field` over names where pick(x)) minus (mean over all names)."""
    series = []
    for d in dates:
        xs = by_date[d]
        sel = [x[field] for x in xs if pick(x, xs)]
        if len(sel) >= 3:
            series.append(st.mean(sel) - st.mean(x[field] for x in xs))
    n = len(series)
    return st.mean(series), st.pstdev(series) / math.sqrt(n), n


def line(label, pick, fields=("cc", "oc")):
    out = []
    for f in fields:
        m, se, n = per_day(pick, f)
        out.append(f"{m:+6.2f} +/-{se:4.2f}")
    print(f"  {label:<62}" + "   ".join(out))


def quintile(i):
    def pick(x, xs):
        cuts = quintile.cache.get(id(xs))
        if cuts is None:
            sc = sorted(y["score"] for y in xs)
            cuts = [sc[int(len(sc) * q / 5)] for q in range(1, 5)]
            quintile.cache[id(xs)] = cuts
        lo = cuts[i - 1] if i > 0 else -1e9
        hi = cuts[i] if i < 4 else 1e9
        return lo <= x["score"] < hi
    return pick
quintile.cache = {}

print("\nNext-session return vs the average liquid stock that day, in %-points (close->close   open->close), mean +/- 1 s.e.")
print("1) The current score, by fifth of the market:")
for i in range(5):
    line(f"Q{i+1}{' (weakest)' if i == 0 else ' (strongest)' if i == 4 else ''}", quintile(i))
top = quintile(4)
top5 = lambda x, xs: x["score"] >= sorted((y["score"] for y in xs), reverse=True)[4]
line("Top 5 names of the whole liquid market", top5)

print("\n2) Inside the strongest fifth, split by one extra input:")
line("5-day run-up > 12%  (extended)", lambda x, xs: top(x, xs) and x["ret5"] > 12)
line("5-day run-up <= 12%", lambda x, xs: top(x, xs) and x["ret5"] <= 12)
line("RSI > 75 (overbought)", lambda x, xs: top(x, xs) and x["rsi"] > 75)
line("RSI <= 75", lambda x, xs: top(x, xs) and x["rsi"] <= 75)
line("yesterday's volume > 2x its 20-day average", lambda x, xs: top(x, xs) and (x["relvol"] or 0) > 2)
line("yesterday's volume < 1x its 20-day average", lambda x, xs: top(x, xs) and (x["relvol"] or 9) < 1)
line("yesterday closed in the top third of its range", lambda x, xs: top(x, xs) and x["close_pos"] > 0.67)
line("yesterday closed in the bottom third of its range", lambda x, xs: top(x, xs) and x["close_pos"] < 0.33)

print("\n3) Inside the strongest fifth, split by what the open itself showed (known at 09:15, not before):")
line("gapped up > 2% at the open", lambda x, xs: top(x, xs) and x["gap"] > 2)
line("gap between -1% and +2%", lambda x, xs: top(x, xs) and -1 <= x["gap"] <= 2)
line("gapped down > 1% at the open", lambda x, xs: top(x, xs) and x["gap"] < -1, fields=("oc", "dd"))

print("\n4) Market regime (equal-weight liquid market's own next-session close->close) - strongest fifth minus average:")
mk = {d: st.mean(x["cc"] for x in by_date[d]) for d in dates}
for name, cond in (("market next day <= -1%", lambda d: mk[d] <= -1), ("market next day between -1% and +1%", lambda d: -1 < mk[d] < 1), ("market next day >= +1%", lambda d: mk[d] >= 1)):
    ds = [d for d in dates if cond(d)]
    ser = [st.mean(x["cc"] for x in by_date[d] if top(x, by_date[d])) - mk[d] for d in ds]
    print(f"  {name:<62}{st.mean(ser):+6.2f} +/-{st.pstdev(ser)/math.sqrt(len(ser)):4.2f}  ({len(ser)} sessions; hindsight - shows how much a regime input could matter, not usable as-is)")

hot = [x for d in dates for x in by_date[d] if top(x, by_date[d])]
print(f"\nStrongest-fifth names: {len(hot):,} stock-sessions; share that fell more than 4% close->close next day: "
      f"{100*sum(1 for x in hot if x['cc']<-4)/len(hot):.1f}%  (whole market: "
      f"{100*sum(1 for d in dates for x in by_date[d] if x['cc']<-4)/sum(len(by_date[d]) for d in dates):.1f}%)")
