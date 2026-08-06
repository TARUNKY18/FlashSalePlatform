package com.flashsale.inventory.application.port;

/**
 * Infrastructure-neutral signal that the pre-warm Redis operation outcome is indeterminate.
 */
public class StockPreWarmUnavailableException extends RuntimeException {

    public StockPreWarmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
