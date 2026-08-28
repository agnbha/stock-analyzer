package com.stockanalyzer.store;

import com.stockanalyzer.model.Instrument;

import java.util.List;
import java.util.Optional;

public interface InstrumentRepository {

    /** Returns the local id for this instrument, inserting it the first time it is seen. */
    long findOrCreate(String symbol, String exchange, String segment);

    Optional<Instrument> find(String symbol, String exchange, String segment);

    Optional<Instrument> byId(long id);

    List<Instrument> findAll();
}
