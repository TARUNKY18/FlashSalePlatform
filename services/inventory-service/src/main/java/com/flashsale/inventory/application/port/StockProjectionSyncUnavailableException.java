package com.flashsale.inventory.application.port;

/**
 * Infrastructure-neutral signal that Redis projection synchronization failed.
 */
public class StockProjectionSyncUnavailableException extends RuntimeException {

    public StockProjectionSyncUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
