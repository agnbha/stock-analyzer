package com.stockanalyzer.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvFileTest {

    @Test
    @DisplayName("reads KEY=value lines the way the shell that sources the file would")
    void readsLikeTheShell() {
        Map<String, String> values = EnvFile.parse("""
                # a comment
                GROWW_PROFILE_NAME=second

                export GROWW_AUTH_MODE=totp
                GROWW_TOTP_TOKEN="ey.abc def"
                GROWW_TOTP_SECRET='ABCD EFGH'
                SPACED = value   # trailing comment
                NOT A KEY=x
                =nothing
                no equals sign
                GROWW_PROFILE_NAME=again
                """);

        assertEquals("again", values.get("GROWW_PROFILE_NAME"), "a key set twice keeps its last value");
        assertEquals("totp", values.get("GROWW_AUTH_MODE"), "export is allowed");
        assertEquals("ey.abc def", values.get("GROWW_TOTP_TOKEN"), "double quotes keep what is inside");
        assertEquals("ABCD EFGH", values.get("GROWW_TOTP_SECRET"), "single quotes too");
        assertEquals("value", values.get("SPACED"), "a bare value ends at the first space");
        assertEquals(5, values.size(), "comments, blank lines and lines that are not KEY=value are skipped: " + values);
    }

    @Test
    @DisplayName("nothing in the file is expanded: it is read as data, never run")
    void nothingIsExpanded() {
        Map<String, String> values = EnvFile.parse("A=$HOME\nB=\"$(whoami)\"\n");
        assertEquals("$HOME", values.get("A"));
        assertEquals("$(whoami)", values.get("B"));
    }
}
