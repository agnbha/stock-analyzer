# Intraday Strategy Primer

Here's the same material with the jargon unpacked.

## Basic vocabulary

- **Candle / bar** — one block of price info for a time slice. A "5‑minute candle" tells you the **open, high, low, and close** price for that 5 minutes.
- **Moving average (MA / EMA)** — the average price over the last N bars, redrawn each bar so it forms a smooth line. It shows the **recent trend direction**. "EMA" just weights recent prices more heavily so it reacts faster.
- **VWAP** — the average price of the day, weighted by how much volume traded at each price. Big institutions use it as their **"fair price" benchmark**. Price above VWAP = buyers in control so far today; below = sellers.
- **ATR (Average True Range)** — the average size of a bar's price swing recently. It's a "how much does this stock normally move" number. Used to **size stops**: a stock that swings ₹10 needs a wider stop than one that swings ₹2.
- **RSI** — a 0–100 meter of how hard price has been pushed one way. High (**>70**) = overbought (ran up fast, may pause/pull back); low (**<30**) = oversold (dropped fast, may bounce).
- **ADX** — a 0–100 meter of how strong the trend is, regardless of direction. High ADX (**>25**) = strong trend; low ADX (**<20**) = choppy, going nowhere.
- **Breakout** — price pushes past a level it had been stuck under (or above), often leading to a fast move.
- **Trend‑following** — betting a move that's underway keeps going.
- **Mean reversion** — betting price has stretched too far from "normal" and will snap back.
- **Stop‑loss** — the price where you admit you're wrong and exit for a small loss.
- **Target** — the price where you take profit.
- **"R"** — your risk on one trade (distance from entry to stop, in rupees). "Made 2R" = profit was twice what you risked.
- **Slippage** — the gap between the price you wanted and the price you actually got.

## Breakout strategies

*For days when a stock is moving strongly.*

### Opening Range Breakout

*The most common beginner strategy.* Watch the first **15 or 30 minutes** and note the highest and lowest price in that window (the "opening range"). If price later closes **above that high**, buy (expecting a run up). If it closes **below the low**, sell/short. Put your stop at the other end of that range, or at its middle. Aim for a profit of about **1–2× the height of the range**.

### Channel breakout

Same idea but continuous: buy when price makes a new high compared to, say, the **last 20 bars**.

### Squeeze breakout

When a stock's bars get unusually small and tight (barely moving), it's often **coiling up**. Trade the first decisive move out of that tight zone. Stop goes just on the other side of the tight zone.

### Gap‑and‑go

If a stock opens much higher than yesterday's close (a **"gap up"**) on heavy volume, buy when it pushes past the high of its first few minutes, betting the momentum continues.

> **Helpful filter:** only take these when the overall market (Nifty) is moving the same direction, and when the breakout bar has **higher‑than‑usual volume** (shows real interest, not a fake‑out).

## Trend‑following strategies

*For days with a steady one‑way drift.*

### Moving‑average crossover

Use two average lines, one **fast** (e.g. 9 bars) and one **slow** (e.g. 21 bars). When the fast line crosses above the slow line and price is above both, **go long**. Exit when it crosses back. Simple, but it gets chopped up on directionless days.

### Supertrend

An indicator that plots a line below price in an uptrend and above price in a downtrend, and **"flips" sides** when the trend changes. Buy on an up‑flip, exit on the down‑flip. Widely used on Indian intraday charts.

### Trend + strength filter

Combine a direction signal (like a crossover) with **ADX**, and only take trades when ADX says the trend is actually strong. Skips most of the chop.

### Trailing stop (ATR‑based)

**Not an entry** — a way to exit. Keep your stop a few ATRs below the highest price reached since you entered, so it ratchets up as the trade works and **locks in profit**.

## Mean‑reversion strategies

*For calm, range‑bound days.*

### Snap‑back to VWAP

When price stretches far away from the day's average price (VWAP), bet it **drifts back toward it**. Enter on the stretch, exit when it returns to VWAP.

### Band reversion (Bollinger Bands)

Bollinger Bands are lines drawn a set distance above and below a moving average, and that distance expands/contracts with volatility. When price closes **outside a band**, bet it comes back; exit at the middle line.

### Quick oversold bounce

Buy when a short‑term RSI hits an extreme low **while the longer‑term trend is still up** (so you're buying a dip in an uptrend, not catching a falling knife). Exit when RSI recovers. This is the family your current **dip‑buy bot** belongs to.

## Level‑based strategies

*Trading specific price lines.*

### Pivot points

A formula turns yesterday's high, low, and close into a set of horizontal lines for today: a central **"pivot"** plus support levels below (**S1, S2, S3**) and resistance levels above (**R1, R2, R3**). Price often reacts at these lines. In a range, bet on **bounces** off them; in a trend, bet on price **breaking through** them. **"Camarilla"** is a variant with tighter lines, better suited to bounce trades.

### Central Pivot Range (CPR)

Three of those lines bunched together form a band. A **narrow CPR** often precedes a big trending day; a **wide CPR** often means a slow range day. Traders use it to decide which type of strategy above to run that day.

### VWAP as a bias line

Simple rule: only look for **buys** while price is above VWAP and VWAP is rising; only look for **shorts** while below. Use VWAP itself as your trailing exit.

### Yesterday's high / low

These two levels from the previous day are watched by everyone. Price **breaking or rejecting** them is a common trade.

## Cross‑stock strategies

*More advanced.*

### Pairs trading

Take two stocks that normally move together (say two PSU banks). When one runs ahead of the other by an unusually large gap, **buy the laggard and short the leader**, betting the gap closes. It's just tracking how far apart they are versus their normal spread — no fancy math required, but you need to be able to short both.

### Relative strength ranking

Rank all 50 Nifty stocks by how much they've moved since the open. When the index is clearly trending, **go long the strongest one or two**.

## The part that matters more than which strategy you pick

**Match the strategy to the day.** Breakout and trend strategies lose money on quiet range days; mean‑reversion strategies lose money on strong trending days. Before trading, get a read on the day (is ADX high? is the CPR narrow? did price open above or below the pivot?) and run the matching type.

### Time rules

- **Skip the first ~5 minutes** — prices are wild and unreliable.
- **Stop taking new trades after about 2:30 pm.**
- **Force‑close everything by ~3:15 pm** (the market shuts at 3:30 and you don't want a position held overnight by accident).

### Risk rules

*Non‑negotiable for beginners.*

- Risk a **small fixed amount per trade** — 0.5% to 1% of your capital, defined by where your stop is.
- Set a **daily loss limit** (e.g. stop trading for the day after losing 2–3× your per‑trade risk).
- **Cap the number of trades per day.**
- **One position per stock.** Take a break after a loss instead of immediately "revenge trading."

### Costs are real

A round trip intraday (buy + sell) costs roughly **0.05–0.15%** in brokerage, taxes, and slippage combined. If a strategy's average winning edge is smaller than that, it **loses money** even if it "looks" profitable on a chart.

## About your bot

- Your code has `"product": "CNC"`, which means **delivery** (shares held in your account, no auto‑exit). For actual day trading you want the **MIS / Intraday** product, which gives you extra buying power and automatically closes positions before the market shuts.
- Checking prices **once a minute is fine** for almost everything above (opening range, VWAP, Supertrend, pivots, crossovers). It's only too slow if you try to **scalp** — grabbing tiny moves in seconds.
- The signal logic lives in `generate_signal` and `calculate_indicators` in your `BaseStrategy` class. Most strategies above are a matter of computing one or two of these indicators and changing the **if‑condition** there.
