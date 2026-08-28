package com.stockanalyzer.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Single shared Jackson instance so every caller gets identical (de)serialization behavior. */
public final class JsonMapper {

    public static final ObjectMapper INSTANCE = new ObjectMapper();

    private JsonMapper() {
    }
}
