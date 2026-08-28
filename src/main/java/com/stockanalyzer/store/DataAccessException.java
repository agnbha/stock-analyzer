package com.stockanalyzer.store;

/** Wraps {@link java.sql.SQLException} so callers never handle checked JDBC errors. */
public class DataAccessException extends RuntimeException {

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public DataAccessException(String message) {
        super(message);
    }
}
