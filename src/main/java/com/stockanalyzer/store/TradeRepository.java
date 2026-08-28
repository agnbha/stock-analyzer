package com.stockanalyzer.store;

import com.stockanalyzer.model.Trade;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TradeRepository {

    /** Inserts trades not already present, keyed on broker trade id. Returns the count inserted. */
    int importAll(List<Trade> trades, String exchange, String segment);

    List<Trade> findRange(LocalDate from, LocalDate to);

    List<Trade> findAllOrdered();

    Optional<Trade> byId(long id);
}
