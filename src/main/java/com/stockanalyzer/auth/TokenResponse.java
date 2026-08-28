package com.stockanalyzer.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Maps the JSON body returned by {@code POST /v1/token/api/access}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenResponse(String token, String tokenRefId, String sessionName, String expiry, boolean isActive) {
}
