# Stock Growth Analyzer

Fetches the last month of daily candles for a configurable list of stocks from
the [Groww Trading API](https://groww.in/trade-api/docs), sends each stock's
candle series to an external ML service for growth-pattern analysis, and
prints a ranked report.

## Requirements

- Java 25+
- Maven 3.8+
- A Groww Trading API key + secret ([generate one here](https://groww.in/trade-api/api-keys))
- A running ML service that accepts the JSON contract in
  [`GrowthAnalysisRequest`](src/main/java/com/stockanalyzer/ml/GrowthAnalysisRequest.java)
  and returns the shape in
  [`GrowthAnalysisResponse`](src/main/java/com/stockanalyzer/ml/GrowthAnalysisResponse.java)
  (this repo does not include a model — bring your own model-serving endpoint)

## Configuration

Edit `src/main/resources/application.properties`, or override any key via an
environment variable (dots become underscores, uppercased — e.g.
`groww.api.key` -> `GROWW_API_KEY`). Prefer env vars for secrets.

| Key | Purpose |
|---|---|
| `groww.api.key` / `groww.api.secret` | Groww API credentials |
| `groww.exchange`, `groww.segment` | Defaults to `NSE` / `CASH` |
| `groww.candle.interval.minutes` | `1440` = daily candles |
| `groww.lookback.days` | How far back to fetch (default 30) |
| `ml.service.url` | Your ML service's prediction endpoint |
| `app.symbols.file` | Classpath file listing symbols, one per line (default `symbols.txt`, pre-populated with 50 NSE large-caps — edit freely) |
| `app.fetch.concurrency` | Parallel fetch/analyze threads (default 5, keep modest — see rate limits below) |

## Build & run

```bash
mvn clean package
java -jar target/stock-analyzer.jar
```

or during development:

```bash
mvn compile exec:java -Dexec.mainClass=com.stockanalyzer.Main
```

## Design

Package layout follows SOLID:

- **`model`** — plain data records (`Candle`, `StockCandleSeries`,
  `GrowthAnalysisResult`, `StockAnalysisOutcome`). No behavior, no dependencies.
- **`auth`** — `GrowwAuthenticator` interface; `ChecksumGrowwAuthenticator`
  implements Groww's API-key+secret checksum token flow and caches the token
  until expiry. A different auth strategy (e.g. TOTP) can be added as another
  implementation without touching any caller.
- **`client`** — `CandleDataClient` interface, with `GrowwCandleDataClient` as
  the Groww-specific implementation. Swapping data providers means writing a
  new implementation, not editing the service layer (**OCP**).
- **`ml`** — `GrowthPatternAnalyzer` interface, with `RestGrowthPatternAnalyzer`
  calling out to a configurable HTTP model-serving endpoint. Swap in a local
  model, a different vendor, or a mock, all behind the same interface.
- **`service`** — `StockGrowthAnalysisService` orchestrates fetch + analyze
  across all symbols concurrently. It depends only on `CandleDataClient` and
  `GrowthPatternAnalyzer` abstractions (**DIP**), is fully unit-testable with
  fakes (see `StockGrowthAnalysisServiceTest`), and isolates per-symbol
  failures so one bad stock doesn't abort the batch.
- **`report`** — `AnalysisReporter` interface; `ConsoleAnalysisReporter` prints
  a ranked table. New output formats (CSV, JSON, a web dashboard) plug in
  without changing the service.
- **`Main`** — the composition root. All concrete-to-interface wiring happens
  here and nowhere else.

## Important caveats

- The historical candle endpoint used
  (`GET /v1/historical/candle/range`) is marked **deprecated** in Groww's docs
  in favor of a newer "Backtesting" data endpoint; it was used here because it
  is the one with a fully documented request/response shape. If Groww removes
  it, update `GrowwCandleDataClient` to target the replacement endpoint.
- The checksum auth algorithm (`SHA-256(apiSecret + epochTimestamp)`, hex
  encoded) was reconstructed from Groww's published cURL docs. If token
  requests start failing with 4xx, re-check the exact concatenation
  order/encoding against the latest docs at
  https://groww.in/trade-api/docs/curl.
- Respect Groww's rate limits (non-trading endpoints: 20 req/sec, 500 req/min).
  `app.fetch.concurrency` defaults to 5 to stay well under that for 50 symbols.
- This project does not include an ML model. `RestGrowthPatternAnalyzer` is a
  thin client against whatever service you point `ml.service.url` at.
