package com.flashsale.inventory.application;

import com.flashsale.inventory.domain.aggregate.Reservation;

/**
 * Sealed result of {@link ReservationCommandService#reserve(CreateReservationCommand)}.
 */
public sealed interface ReservationCreatedResult
        permits ReservationCreatedResult.Created,
                ReservationCreatedResult.IdempotentReplay,
                ReservationCreatedResult.SoldOut,
                ReservationCreatedResult.DuplicateReservation {

    /** Stock was decremented and a new reservation was persisted. */
    record Created(Reservation reservation) implements ReservationCreatedResult {}

    /** A reservation with the same idempotency key already exists; returning the original. */
    record IdempotentReplay(Reservation reservation) implements ReservationCreatedResult {}

    /** Stock is insufficient for this sale. */
    record SoldOut() implements ReservationCreatedResult {}

    /** A duplicate in-flight creation was detected by the Redis NX guard. */
    record DuplicateReservation() implements ReservationCreatedResult {}
}
