package com.flashsale.inventory.application.port;

import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.vo.ReservationId;
import java.util.Optional;

/**
 * Application boundary for loading and saving Reservation aggregates.
 */
public interface ReservationRepository {

    Optional<Reservation> findById(ReservationId id);

    Reservation save(Reservation reservation);
}
