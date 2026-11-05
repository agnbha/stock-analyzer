"""Read-only. Which candidate inputs rank the liquid market's FUTURE returns, at which horizon, and do they hold in both halves?

Per session: rank-correlation (Spearman) of the input with the forward return k sessions ahead, across ~770 liquid names.
Mean over sessions; the standard error uses every k-th session so overlapping windows are not counted twice.
'H1'/'H2' = first/last half of the sessions: an input that only works in one half is not trusted.
"""
import pathlib, sys

# Read-only: the database is opened with mode=ro. Pass another path as the first argument to use a copy.
DB = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[2] / "data" / "stock-analyzer.db"
URI = "file:" + str(DB) + "?mode=ro"
import math, sqlite3, statistics as st
from collections import defaultdict

con = sqlite3.connect(URI, uri=True)
bars = defaultdict(list)
for iid, d, o, h, l, c, v in con.execute("SELECT instrument_id, session_date, open, high, low, close, volume FROM daily_bar "
                                         "WHERE close>0 AND open>0 ORDER BY instrument_id, session_date"):
    bars[iid].append((d, o, h, l, c, v))

HZ = (1, 5, 10, 20)
FEATS = ["score (current)", "mom20", "mom60", "mom120 (skip last 5)", "near 120d high", "low volatility", "short-term reversal 5d",
         "short-term reversal 1d", "rel. volume (up days)", "low ATR%"]
cs = defaultdict(list)  # date -> list of (features dict, fwd dict)
for iid, s in bars.items():
    n = len(s)
    if n < 150:
        continue
    cl = [x[4] for x in s]
    gain = loss = 0.0
    rsi = [None] * n
    for k in range(1, n):
        ch = cl[k] - cl[k - 1]; g, ls = max(ch, 0), max(-ch, 0)
        if k <= 14:
            gain += g / 14; loss += ls / 14
            if k == 14: rsi[k] = 100 if loss == 0 else 100 - 100 / (1 + gain / loss)
        else:
            gain = (gain * 13 + g) / 14; loss = (loss * 13 + ls) / 14
            rsi[k] = 100 if loss == 0 else 100 - 100 / (1 + gain / loss)
    for k in range(125, n - 1):
        d, o, h, l, c, v = s[k]
        w20 = s[k - 19:k + 1]
        if st.median(x[4] * x[5] for x in w20) < 1e8 or rsi[k] is None:
            continue
        sma = sum(x[4] for x in w20) / 20
        r1 = (c / cl[k - 1] - 1) * 100; r5 = (c / cl[k - 5] - 1) * 100
        rets = [(cl[j] / cl[j - 1] - 1) * 100 for j in range(k - 19, k + 1)]
        atr = sum((max(x[2], cl[k - 19 + i - 1] if i else x[2]) - min(x[3], cl[k - 19 + i - 1] if i else x[3])) / x[4] * 100 for i, x in enumerate(w20)) / 20
        avgv = sum(x[5] for x in s[k - 19:k]) / 19
        f = {
            "score (current)": (c / sma - 1) * 100 + r5 + (rsi[k] - 50) / 10 - max(0, rsi[k] - 75) / 5,
            "mom20": (c / cl[k - 20] - 1), "mom60": (c / cl[k - 60] - 1),
            "mom120 (skip last 5)": (cl[k - 5] / cl[k - 125] - 1),
            "near 120d high": c / max(x[2] for x in s[k - 119:k + 1]),
            "low volatility": -st.pstdev(rets), "short-term reversal 5d": -r5, "short-term reversal 1d": -r1,
            "rel. volume (up days)": (v / avgv if avgv else 1) * (1 if r1 > 0 else -1), "low ATR%": -atr,
        }
        fw = {hz: (cl[k + hz] / c - 1) for hz in HZ if k + hz < n}
        cs[d].append((f, fw))


def rank(xs):
    order = sorted(range(len(xs)), key=xs.__getitem__)
    r = [0.0] * len(xs)
    for pos, i in enumerate(order): r[i] = pos
    return r


def ic(pairs):
    if len(pairs) < 150: return None
    a = rank([p[0] for p in pairs]); b = rank([p[1] for p in pairs])
    ma, mb = st.mean(a), st.mean(b)
    num = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    den = math.sqrt(sum((x - ma) ** 2 for x in a) * sum((y - mb) ** 2 for y in b))
    return num / den if den else None


dates = sorted(cs)
half = dates[len(dates) // 2]
print(f"sessions {dates[0]}..{dates[-1]} ({len(dates)}), median names/session {int(st.median(len(cs[d]) for d in dates))}; halves split at {half}\n")
print(f"{'input':<26}" + "".join(f"{'h='+str(h)+'d  IC (t)  H1 / H2':>30}" for h in HZ))
for name in FEATS:
    line = f"{name:<26}"
    for hz in HZ:
        per = {}
        for d in dates:
            pairs = [(f[name], fw[hz]) for f, fw in cs[d] if hz in fw]
            v = ic(pairs)
            if v is not None: per[d] = v
        ds = sorted(per)
        mean = st.mean(per.values())
        sub = [per[d] for d in ds[::hz]]
        se = st.pstdev(sub) / math.sqrt(len(sub))
        h1 = st.mean(per[d] for d in ds if d < half); h2 = st.mean(per[d] for d in ds if d >= half)
        line += f"{mean:+7.3f} ({mean/se:+5.1f}) {h1:+6.3f}/{h2:+6.3f}   "
    print(line)
print("\n|t| above ~2 AND the same sign in both halves is the bar for an input worth wiring in.")
