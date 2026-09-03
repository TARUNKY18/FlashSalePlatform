package com.flashsale.order.application.port;

/** Signals that the best-effort idempotency cache could not be read or written. */
public class IdempotencyCacheUnavailableException extends RuntimeException {

    public IdempotencyCacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
