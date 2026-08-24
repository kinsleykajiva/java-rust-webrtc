package io.github.kinsleykajiva.server.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Uniform error block attached to any {@code response}/{@code event} message.
 * Absent ({@code null}) means success.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "code", "message" })
public final class ErrorDetail {

    /** 4000 = protocol/validation error, 5000 = server/internal error. */
    public int code;
    public String message;

    public ErrorDetail() {
    }

    public ErrorDetail(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public static final int CODE_BAD_REQUEST = 4000;
    public static final int CODE_INTERNAL = 5000;
}
