package com.stockanalyzer.store.jdbc;

import com.stockanalyzer.canslim.CompanyProfile;
import com.stockanalyzer.store.CompanyProfileRepository;
import com.stockanalyzer.store.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.YearMonth;
import java.util.Optional;

public final class SqliteCompanyProfileRepository implements CompanyProfileRepository {

    private final Database database;

    public SqliteCompanyProfileRepository(Database database) {
        this.database = database;
    }

    @Override
    public void save(long instrumentId, CompanyProfile profile, long fetchedAtEpoch) {
        database.inTransaction(connection -> {
            clear(connection, instrumentId);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO company_profile
                      (instrument_id, fetched_at, results_quarter, holding_quarter, about, classification,
                       market_cap_cr, price, high_52w, low_52w, pe, book_value, dividend_yield, roce, roe,
                       face_value, eps_ttm, debt_to_equity, sales_growth_ttm, sales_growth_3y,
                       profit_growth_ttm, profit_growth_3y)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
                int i = 1;
                insert.setLong(i++, instrumentId);
                insert.setLong(i++, fetchedAtEpoch);
                insert.setString(i++, month(profile.resultsQuarter()));
                insert.setString(i++, month(profile.holdingQuarter()));
                insert.setString(i++, profile.about());
                insert.setString(i++, profile.classification());
                for (Double value : new Double[]{profile.marketCapCr(), profile.price(), profile.high52w(),
                        profile.low52w(), profile.pe(), profile.bookValue(), profile.dividendYield(), profile.roce(),
                        profile.roe(), profile.faceValue(), profile.epsTtm(), profile.debtToEquity(),
                        profile.salesGrowthTtm(), profile.salesGrowth3y(), profile.profitGrowthTtm(),
                        profile.profitGrowth3y()}) {
                    JdbcSupport.setNullableDouble(insert, i++, value);
                }
                insert.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO company_quarter (instrument_id, period, sales, net_profit, eps) VALUES (?,?,?,?,?)")) {
                for (CompanyProfile.Quarter quarter : profile.quarters()) {
                    insert.setLong(1, instrumentId);
                    insert.setString(2, month(quarter.period()));
                    JdbcSupport.setNullableDouble(insert, 3, quarter.sales());
                    JdbcSupport.setNullableDouble(insert, 4, quarter.netProfit());
                    JdbcSupport.setNullableDouble(insert, 5, quarter.eps());
                    insert.executeUpdate();
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO company_holding
                      (instrument_id, period, promoters, fiis, diis, government, public, others, shareholders)
                    VALUES (?,?,?,?,?,?,?,?,?)""")) {
                for (CompanyProfile.Holding holding : profile.holdings()) {
                    insert.setLong(1, instrumentId);
                    insert.setString(2, month(holding.period()));
                    JdbcSupport.setNullableDouble(insert, 3, holding.promoters());
                    JdbcSupport.setNullableDouble(insert, 4, holding.fiis());
                    JdbcSupport.setNullableDouble(insert, 5, holding.diis());
                    JdbcSupport.setNullableDouble(insert, 6, holding.government());
                    JdbcSupport.setNullableDouble(insert, 7, holding.publicShare());
                    JdbcSupport.setNullableDouble(insert, 8, holding.others());
                    JdbcSupport.setNullableDouble(insert, 9, holding.shareholders());
                    insert.executeUpdate();
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO company_announcement (instrument_id, position, announced_at, title, summary, url)
                    VALUES (?,?,?,?,?,?)""")) {
                int position = 1;
                for (CompanyProfile.Announcement announcement : profile.announcements()) {
                    insert.setLong(1, instrumentId);
                    insert.setInt(2, position++);
                    if (announcement.at() == null) {
                        insert.setNull(3, Types.INTEGER);
                    } else {
                        insert.setLong(3, announcement.at().getEpochSecond());
                    }
                    insert.setString(4, announcement.title());
                    insert.setString(5, announcement.summary());
                    insert.setString(6, announcement.url());
                    insert.executeUpdate();
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO company_concall
                      (instrument_id, position, period, transcript_url, presentation_url, recording_url, summary_url)
                    VALUES (?,?,?,?,?,?,?)""")) {
                int position = 1;
                for (CompanyProfile.Concall concall : profile.concalls()) {
                    insert.setLong(1, instrumentId);
                    insert.setInt(2, position++);
                    insert.setString(3, month(concall.period()));
                    insert.setString(4, concall.transcriptUrl());
                    insert.setString(5, concall.presentationUrl());
                    insert.setString(6, concall.recordingUrl());
                    insert.setString(7, concall.summaryUrl());
                    insert.executeUpdate();
                }
            }
        });
    }

    @Override
    public void saveEmpty(long instrumentId, long fetchedAtEpoch) {
        database.inTransaction(connection -> {
            clear(connection, instrumentId);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO company_profile (instrument_id, fetched_at) VALUES (?,?)")) {
                insert.setLong(1, instrumentId);
                insert.setLong(2, fetchedAtEpoch);
                insert.executeUpdate();
            }
        });
    }

    @Override
    public Optional<Long> fetchedAt(long instrumentId) {
        return database.read(connection -> {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT fetched_at FROM company_profile WHERE instrument_id = ?")) {
                select.setLong(1, instrumentId);
                try (ResultSet rs = select.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
                }
            }
        });
    }

    private static void clear(java.sql.Connection connection, long instrumentId) throws java.sql.SQLException {
        for (String table : new String[]{"company_profile", "company_quarter", "company_holding",
                "company_announcement", "company_concall"}) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE instrument_id = ?")) {
                delete.setLong(1, instrumentId);
                delete.executeUpdate();
            }
        }
    }

    private static String month(YearMonth month) {
        return month == null ? null : month.toString();
    }
}
