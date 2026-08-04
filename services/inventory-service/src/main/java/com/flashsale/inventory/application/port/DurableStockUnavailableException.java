package com.flashsale.inventory.application.port;

/**
 * Infrastructure-neutral signal that authoritative durable stock could not be accessed.
 */
public class DurableStockUnavailableException extends RuntimeException {

    public DurableStockUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
