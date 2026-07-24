package com.flashsale.inventory.application.port;

/**
 * Infrastructure-neutral signal that the primary stock counter cannot be reached.
 */
public class StockDecrementUnavailableException extends RuntimeException {

    public StockDecrementUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
