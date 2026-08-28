package com.stockanalyzer.trade;

import com.stockanalyzer.model.Trade;

import java.time.LocalDate;
import java.util.List;

/**
 * Where executed fills come from. The broker trade book is authoritative and
 * carries real charges; CSV and manual entry cover history that predates this
 * tool and one-off corrections.
 */
public interface ExecutionDataClient {

    List<Trade> fetchTrades(LocalDate from, LocalDate to);
}
