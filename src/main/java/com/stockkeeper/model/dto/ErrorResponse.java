package com.stockkeeper.model.dto;

import java.time.Instant;
import java.util.List;

/**
 * Standard error response returned for validation failures (400)
 * and not-found errors (404).
 *
 * @param timestamp  when the error occurred (ISO-8601)
 * @param status     HTTP status code
 * @param error      short error label (e.g. "Bad Request")
 * @param messages   list of specific error descriptions
 * @param path       the request path that triggered the error
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        List<String> messages,
        String path
) {
}
