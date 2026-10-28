package com.stockanalyzer.trade;

import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.TradeReasonRepository;
import com.stockanalyzer.store.TradeRepository;

import java.time.LocalDate;
import java.util.List;

/**
 * Pulls fills into the journal and brings everything derived from them up to date:
 * matched lots, open positions and the reason each trade was taken. One path for
 * the CLI and for the daemon, so a fill looks the same however it arrived.
 *
 * <p>Every step replaces or ignores what it has already seen, so running it again
 * on the same day is harmless.
 */
public final class BrokerFillSync {

    private final TradeJournalService journal;
    private final TradeRepository tradeRepository;
    private final TradeReasonRepository tradeReasonRepository;
    private final ReasonAttributor reasonAttributor;

    public BrokerFillSync(TradeJournalService journal, TradeRepository tradeRepository,
                          TradeReasonRepository tradeReasonRepository, ReasonAttributor reasonAttributor) {
        this.journal = journal;
        this.tradeRepository = tradeRepository;
        this.tradeReasonRepository = tradeReasonRepository;
        this.reasonAttributor = reasonAttributor;
    }

    public Result sync(ExecutionDataClient client, LocalDate from, LocalDate to) {
        int inserted = journal.importTrades(client, from, to);
        FifoLotMatcher.Result matched = journal.rebuild();
        int attributed = attributeReasons(from, to);
        return new Result(inserted, matched.lots().size(), matched.openPositions().size(), attributed);
    }

    private int attributeReasons(LocalDate from, LocalDate to) {
        List<Trade> trades = tradeRepository.findRange(from, to);
        if (trades.isEmpty()) {
            return 0;
        }
        tradeReasonRepository.deleteForTrades(trades.stream().map(Trade::id).toList());
        List<TradeReasonRepository.TradeReason> reasons = reasonAttributor.attribute(trades);
        tradeReasonRepository.upsertAll(reasons);
        return reasons.size();
    }

    public record Result(int newTrades, int closedLots, int openPositions, int reasonsAttributed) {
    }
}
