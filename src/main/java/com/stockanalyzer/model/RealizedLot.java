package com.stockanalyzer.model;

/** One closed position: a buy lot matched FIFO against a sell. */
public record RealizedLot(long id,
                          String symbol,
                          long buyTradeId,
                          long sellTradeId,
                          Product product,
                          int quantity,
                          double buyPrice,
                          double sellPrice,
                          long openedTs,
                          long closedTs,
                          int holdingMinutes,
                          double grossPnl,
                          double chargesAllocated,
                          double netPnl,
                          double returnPct) {

    public boolean isWin() {
        return netPnl > 0;
    }
}
