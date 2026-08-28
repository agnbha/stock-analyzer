package com.stockanalyzer.trade;

import com.stockanalyzer.model.Charges;
import com.stockanalyzer.model.OpenPosition;
import com.stockanalyzer.model.PeriodType;
import com.stockanalyzer.model.PnlSummary;
import com.stockanalyzer.model.RealizedLot;
import com.stockanalyzer.model.Trade;
import com.stockanalyzer.store.InstrumentRepository;
import com.stockanalyzer.store.PnlPeriodRepository;
import com.stockanalyzer.store.RealizedLotRepository;
import com.stockanalyzer.store.TradeRepository;
import com.stockanalyzer.store.TradingDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The trade journal: import fills, match them into closed lots, and roll those
 * up into a gain/loss statement for any period.
 *
 * <p>Matching is a pure recompute from the stored trades, so a bug in the
 * matcher or a change to the charge schedule can never corrupt the underlying
 * record - re-running fixes it.
 */
public final class TradeJournalService {

    private static final Logger log = LoggerFactory.getLogger(TradeJournalService.class);

    private final TradeRepository tradeRepository;
    private final RealizedLotRepository realizedLotRepository;
    private final PnlPeriodRepository pnlPeriodRepository;
    private final InstrumentRepository instrumentRepository;
    private final TradingDayRepository tradingDayRepository;
    private final FifoLotMatcher matcher;
    private final ChargeModel chargeModel;
    private final PeriodAggregator aggregator;
    private final PeriodBounds bounds;
    private final boolean preferBrokerActuals;
    private final String exchange;
    private final String segment;

    public TradeJournalService(TradeRepository tradeRepository,
                               RealizedLotRepository realizedLotRepository,
                               PnlPeriodRepository pnlPeriodRepository,
                               InstrumentRepository instrumentRepository,
                               TradingDayRepository tradingDayRepository,
                               FifoLotMatcher matcher,
                               ChargeModel chargeModel,
                               PeriodAggregator aggregator,
                               PeriodBounds bounds,
                               boolean preferBrokerActuals,
                               String exchange,
                               String segment) {
        this.tradeRepository = tradeRepository;
        this.realizedLotRepository = realizedLotRepository;
        this.pnlPeriodRepository = pnlPeriodRepository;
        this.instrumentRepository = instrumentRepository;
        this.tradingDayRepository = tradingDayRepository;
        this.matcher = matcher;
        this.chargeModel = chargeModel;
        this.aggregator = aggregator;
        this.bounds = bounds;
        this.preferBrokerActuals = preferBrokerActuals;
        this.exchange = exchange;
        this.segment = segment;
    }

    /** Imports fills, filling in modelled charges only where the broker gave none. */
    public int importTrades(ExecutionDataClient client, LocalDate from, LocalDate to) {
        List<Trade> fetched = client.fetchTrades(from, to);
        List<Trade> priced = new ArrayList<>(fetched.size());
        for (Trade trade : fetched) {
            boolean hasBrokerCharges = trade.chargesSource() == Trade.ChargesSource.BROKER
                    && trade.chargesTotal() > 0;
            if (preferBrokerActuals && hasBrokerCharges) {
                priced.add(trade);
                continue;
            }
            Charges charges = chargeModel.compute(trade);
            priced.add(trade.withCharges(charges.total(), describe(charges), Trade.ChargesSource.MODELLED));
        }
        int inserted = tradeRepository.importAll(priced, exchange, segment);
        log.info("Imported {} new trades ({} fetched) for {}..{}", inserted, fetched.size(), from, to);
        return inserted;
    }

    /** Re-matches every stored trade. Safe to run any time; it replaces all derived rows. */
    public FifoLotMatcher.Result rebuild() {
        FifoLotMatcher.Result result = matcher.match(tradeRepository.findAllOrdered());
        realizedLotRepository.replaceAll(result.lots(), result.openPositions(), exchange, segment);
        log.info("Matched {} closed lots, {} positions still open",
                result.lots().size(), result.openPositions().size());
        return result;
    }

    public PnlSummary pnl(PeriodType periodType, LocalDate anchor, String symbolOrNull) {
        LocalDate start = bounds.startOf(periodType, anchor);
        LocalDate end = bounds.endOf(periodType, anchor);
        List<Trade> trades = tradeRepository.findRange(start, end);
        List<RealizedLot> lots = realizedLotRepository.findClosedBetween(start, end);
        PnlSummary summary = aggregator.aggregate(periodType, start, end, symbolOrNull,
                trades, lots, unrealised(end));
        pnlPeriodRepository.upsert(summary);
        return summary;
    }

    /** Sub-periods of a period - the weekly breakdown inside a month, and so on. */
    public List<PnlSummary> breakdown(PeriodType periodType, LocalDate anchor, String symbolOrNull) {
        PeriodType childType = switch (periodType) {
            case FY -> PeriodType.MONTH;
            case MONTH -> PeriodType.WEEK;
            case WEEK -> PeriodType.DAY;
            case DAY -> null;
        };
        if (childType == null) {
            return List.of();
        }
        LocalDate start = bounds.startOf(periodType, anchor);
        LocalDate end = bounds.endOf(periodType, anchor);
        List<PnlSummary> parts = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            LocalDate childStart = bounds.startOf(childType, cursor);
            LocalDate childEnd = bounds.endOf(childType, cursor);
            LocalDate clampedStart = childStart.isBefore(start) ? start : childStart;
            LocalDate clampedEnd = childEnd.isAfter(end) ? end : childEnd;
            parts.add(aggregator.aggregate(childType, clampedStart, clampedEnd, symbolOrNull,
                    tradeRepository.findRange(clampedStart, clampedEnd),
                    realizedLotRepository.findClosedBetween(clampedStart, clampedEnd), 0));
            cursor = childEnd.plusDays(1);
        }
        return parts;
    }

    /** Open positions marked to the last stored close - no extra API calls needed. */
    public double unrealised(LocalDate asOf) {
        double total = 0;
        for (OpenPosition position : realizedLotRepository.openPositions()) {
            long instrumentId = instrumentRepository.findOrCreate(position.symbol(), exchange, segment);
            Double mark = tradingDayRepository.latestCloseOnOrBefore(instrumentId, asOf).orElse(null);
            if (mark != null) {
                total += (mark - position.avgCost()) * position.quantity();
            }
        }
        return total;
    }

    public List<OpenPosition> openPositions() {
        return realizedLotRepository.openPositions();
    }

    private static String describe(Charges charges) {
        return String.format("{\"brokerage\":%.2f,\"stt\":%.2f,\"exchange\":%.2f,\"sebi\":%.2f,"
                        + "\"stamp\":%.2f,\"gst\":%.2f}",
                charges.brokerage(), charges.stt(), charges.exchangeTxn(), charges.sebi(),
                charges.stampDuty(), charges.gst());
    }
}
