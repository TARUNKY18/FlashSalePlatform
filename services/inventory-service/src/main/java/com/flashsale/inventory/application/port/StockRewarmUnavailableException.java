package com.flashsale.inventory.application.port;

/**
 * Infrastructure-neutral signal that the primary stock counter could not be re-warmed.
 */
public class StockRewarmUnavailableException extends RuntimeException {

    public StockRewarmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
