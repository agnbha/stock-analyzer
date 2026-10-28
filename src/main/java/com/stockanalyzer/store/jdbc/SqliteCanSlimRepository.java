package com.stockanalyzer.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.stockanalyzer.canslim.CanSlimScore;
import com.stockanalyzer.canslim.MarketAssessment;
import com.stockanalyzer.canslim.PriceAction;
import com.stockanalyzer.store.CanSlimRepository;
import com.stockanalyzer.store.Database;
import com.stockanalyzer.util.JsonMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Map;

public final class SqliteCanSlimRepository implements CanSlimRepository {

    private final Database database;

    public SqliteCanSlimRepository(Database database) {
        this.database = database;
    }

    @Override
    public long saveRun(long runAtEpoch, String query, int screened, MarketAssessment market,
                        Map<Long, CanSlimScore> scores) {
        return database.inTransaction(connection -> {
            long runId;
            try (PreparedStatement run = connection.prepareStatement("""
                    INSERT INTO canslim_run
                      (run_at, as_of, query, screened, evaluated, market_symbol, market_state,
                       market_close, market_sma50, market_sma200, distribution_days, market_note)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS)) {
                run.setLong(1, runAtEpoch);
                run.setString(2, market.asOf() == null ? null : market.asOf().toString());
                run.setString(3, query);
                run.setInt(4, screened);
                run.setInt(5, scores.size());
                run.setString(6, market.indexSymbol());
                run.setString(7, market.state().name());
                JdbcSupport.setNullableDouble(run, 8, market.close());
                JdbcSupport.setNullableDouble(run, 9, market.sma50());
                JdbcSupport.setNullableDouble(run, 10, market.sma200());
                run.setInt(11, market.distributionDays());
                run.setString(12, market.note());
                run.executeUpdate();
                try (ResultSet keys = run.getGeneratedKeys()) {
                    keys.next();
                    runId = keys.getLong(1);
                }
            }

            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO canslim_score
                      (run_id, instrument_id, source, as_of, close, letters, letters_passed,
                       c_pass, a_pass, n_pass, s_pass, l_pass, i_pass, m_pass,
                       qtr_profit_growth, qtr_sales_growth, profit_growth_3y, roe, institutional_change,
                       rs_rating, weighted_return, up_down_volume, pct_from_high, sma50, sma200,
                       volume_ratio, dry_up_ratio, pivot, pivot_date, base_sessions, base_depth_pct,
                       pct_from_pivot, breakout_date, buy_point, signal, reason, ratios_json)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
                for (Map.Entry<Long, CanSlimScore> entry : scores.entrySet()) {
                    bind(insert, runId, entry.getKey(), entry.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return runId;
        });
    }

    private static void bind(PreparedStatement ps, long runId, long instrumentId, CanSlimScore score)
            throws SQLException {
        PriceAction pa = score.priceAction();
        PriceAction.Base base = pa == null ? null : pa.base();
        PriceAction.Breakout breakout = pa == null ? null : pa.breakout();
        CanSlimScore.Letters letters = score.letters();

        int k = 1;
        ps.setLong(k++, runId);
        ps.setLong(k++, instrumentId);
        ps.setString(k++, score.source().name());
        ps.setString(k++, pa == null ? null : pa.asOf().toString());
        JdbcSupport.setNullableDouble(ps, k++, pa == null ? null : pa.close());
        ps.setString(k++, letters.display());
        ps.setInt(k++, letters.passed());
        for (Boolean letter : new Boolean[]{letters.c(), letters.a(), letters.n(), letters.s(),
                letters.l(), letters.i(), letters.m()}) {
            JdbcSupport.setNullableInt(ps, k++, letter == null ? null : letter ? 1 : 0);
        }
        JdbcSupport.setNullableDouble(ps, k++, score.qtrProfitGrowth());
        JdbcSupport.setNullableDouble(ps, k++, score.qtrSalesGrowth());
        JdbcSupport.setNullableDouble(ps, k++, score.profitGrowth3y());
        JdbcSupport.setNullableDouble(ps, k++, score.roe());
        JdbcSupport.setNullableDouble(ps, k++, score.institutionalChange());
        JdbcSupport.setNullableInt(ps, k++, score.rsRating());
        JdbcSupport.setNullableDouble(ps, k++, pa == null ? null : pa.weightedReturn());
        JdbcSupport.setNullableDouble(ps, k++, pa == null ? null : pa.upDownVolumeRatio());
        JdbcSupport.setNullableDouble(ps, k++, pa == null ? null : pa.pctFromHigh());
        JdbcSupport.setNullableDouble(ps, k++, pa == null ? null : pa.sma50());
        JdbcSupport.setNullableDouble(ps, k++, pa == null ? null : pa.sma200());
        JdbcSupport.setNullableDouble(ps, k++, pa == null ? null : pa.volumeRatio());
        JdbcSupport.setNullableDouble(ps, k++, pa == null ? null : pa.dryUpRatio());
        // The pivot that matters is the breakout's once there is one; before that, the base's.
        JdbcSupport.setNullableDouble(ps, k++, breakout != null ? Double.valueOf(breakout.pivot())
                : base != null ? Double.valueOf(base.pivot()) : null);
        ps.setString(k++, base == null ? null : base.pivotDate().toString());
        JdbcSupport.setNullableInt(ps, k++, base == null ? null : base.sessions());
        JdbcSupport.setNullableDouble(ps, k++, base == null ? null : base.depthPct());
        JdbcSupport.setNullableDouble(ps, k++, breakout != null ? Double.valueOf(breakout.pctFromPivot())
                : base != null ? Double.valueOf(base.pctFromPivot()) : null);
        ps.setString(k++, breakout == null ? null : breakout.date().toString());
        JdbcSupport.setNullableDouble(ps, k++, score.buyPoint());
        ps.setString(k++, score.signal().name());
        ps.setString(k++, score.reason());
        if (score.ratios().isEmpty()) {
            ps.setNull(k, Types.VARCHAR);
        } else {
            try {
                ps.setString(k, JsonMapper.INSTANCE.writeValueAsString(score.ratios()));
            } catch (JsonProcessingException e) {
                ps.setNull(k, Types.VARCHAR);
            }
        }
    }
}
