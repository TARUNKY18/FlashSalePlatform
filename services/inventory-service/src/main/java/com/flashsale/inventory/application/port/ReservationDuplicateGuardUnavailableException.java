package com.flashsale.inventory.application.port;

/**
 * Signals that the Redis reservation duplicate guard had an indeterminate transport outcome.
 * The database partial unique index remains the durable duplicate guard.
 */
public class ReservationDuplicateGuardUnavailableException extends RuntimeException {

    public ReservationDuplicateGuardUnavailableException(String message) {
        super(message);
    }

    public ReservationDuplicateGuardUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
