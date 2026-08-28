package com.stockanalyzer.ml;

import com.stockanalyzer.model.StockCandleSeries;

import java.util.List;

/** Wire format POSTed to the configured ML service endpoint. */
record GrowthAnalysisRequest(String symbol, String exchange, String segment, List<CandleDto> candles) {

    static GrowthAnalysisRequest from(StockCandleSeries series) {
        List<CandleDto> candles = series.candles().stream().map(CandleDto::from).toList();
        return new GrowthAnalysisRequest(series.symbol(), series.exchange(), series.segment(), candles);
    }
}
