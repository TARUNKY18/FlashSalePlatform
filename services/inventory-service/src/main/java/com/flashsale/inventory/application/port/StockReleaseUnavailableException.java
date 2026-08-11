package com.flashsale.inventory.application.port;

/**
 * Infrastructure-neutral signal that the Redis stock release outcome is indeterminate.
 */
public class StockReleaseUnavailableException extends RuntimeException {

    public StockReleaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public StockReleaseUnavailableException(String message) {
        super(message);
    }
}
