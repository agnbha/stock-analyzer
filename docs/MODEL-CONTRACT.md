# Model service contract

Part 2's trained model is served over HTTP, exactly like the existing
`GrowthPatternAnalyzer`: this repo brings the features, the caller, the storage
and the evaluation, and you bring the model.

`model.enabled=false` by default. With it off, `HotWindowSignalModel` — the
statistical time-of-day prior — serves every request, so alerts and the live
monitor work end to end with no service running at all. With it on,
`FallbackSignalModel` calls your service and silently falls back to that same
prior whenever the service is unavailable.

## Request

`POST` to `model.service.url` (default `http://localhost:8001/predict/intraday`):

```json
{
  "symbol": "RELIANCE",
  "session_date": "2026-08-27",
  "horizon_minutes": 30,
  "feature_names": ["minutes_since_open", "session_progress_sin", "..."],
  "rows": [
    {"ts_epoch": 1787802300, "values": [0.0, 0.0, 1.0, 4.0, "..."]}
  ]
}
```

`values` is positional and aligned with `feature_names`, which is sent on every
request precisely so a column mismatch fails loudly instead of scoring against
the wrong data.

## Response

```json
{
  "model_version": "lgbm-triple-barrier/2026-08-01",
  "predictions": [
    {"ts_epoch": 1787802300, "signal": "ENTRY", "probability": 0.71,
     "reason": "volume spike into an opening-range break"}
  ]
}
```

`signal` is `ENTRY`, `EXIT` or `NEUTRAL`. `reason` is optional and is shown to
you in alerts, so it is worth filling in. Rows may be omitted; anything not
returned is treated as no signal.

## The feature contract

The 26 features are defined once, in `FeatureExtractor.FEATURE_NAMES`, and are
append-only: reordering or renaming a column silently invalidates a trained
model.

`src/test/resources/feature-parity.json` pins them. It contains a sample
session's candles, the prior session's close/high/low, the column order, and the
expected feature vector for every candle. Your training pipeline should compute
features from those candles and assert it reproduces the same numbers; the Java
side asserts the same thing on every build (`FeatureParityTest`). Without a
shared fixture, training and serving drift apart and every metric becomes
meaningless.

After an intended feature change:

```bash
mvn test -Dtest=FeatureParityTest -Dregenerate=true
```

then retrain against the new fixture.

## What the Java side does with the answer

Every prediction is stored in `prediction` with its `model_version`. The nightly
`DailyAnalysisMain evaluate` job fills in `realized_return_pct` from the
now-complete tape — the best exit available within the horizon — so accuracy is
measured against reality rather than assumed.

Two things are worth holding to before letting a model drive alerts:

- **Validate with purged walk-forward splits.** Labels look forward over the
  horizon, so a random train/test split leaks the future. Train on months 1..k,
  validate on k+1, and drop samples within one horizon of the boundary.
- **Beat the baseline on a held-out month.** The prior is already serving. A
  model that cannot beat it is a null result — a normal outcome on a few months
  of one exchange's data — and `alerts.model.min-probability` should stay
  unreachable until it does.
