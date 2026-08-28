package com.stockanalyzer.store;

import com.stockanalyzer.model.MarketEvent;

import java.time.LocalDate;
import java.util.List;

public interface MarketEventRepository {

    void saveAll(long instrumentId, List<MarketEvent> events, String detectorVersion);

    List<MarketEvent> find(long instrumentId, LocalDate sessionDate);

    List<MarketEvent> findAllForSession(LocalDate sessionDate);
}
