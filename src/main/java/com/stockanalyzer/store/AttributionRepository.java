package com.stockanalyzer.store;

import com.stockanalyzer.model.TradeAttribution;

import java.util.List;

public interface AttributionRepository {

    void upsertAll(List<TradeAttribution> attributions);

    List<TradeAttribution> findForTrades(List<Long> tradeIds);
}
