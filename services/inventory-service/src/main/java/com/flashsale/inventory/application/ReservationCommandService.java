package com.flashsale.inventory.application;

import com.flashsale.inventory.application.port.ReservationDuplicateGuardPort;
import com.flashsale.inventory.application.port.ReservationDuplicateGuardUnavailableException;
import com.flashsale.inventory.application.port.ReservationRepository;
import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.vo.ReservationExpiry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the reservation-creation use case through application ports.
 *
 * <p>Flow:
 * <ol>
 *   <li>Idempotency key lookup (idempotent replay path).</li>
 *   <li>Redis NX duplicate guard — falls through to DB guard on unavailability.</li>
 *   <li>Stock decrement via {@link StockCounterService}.</li>
 *   <li>Reservation domain creation and persistence.</li>
 * </ol>
 */
@Service
public class ReservationCommandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationCommandService.class);
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(10);

    private final ReservationRepository reservationRepository;
    private final ReservationDuplicateGuardPort duplicateGuardPort;
    private final StockCounterService stockCounterService;
    private final Clock clock;

    public ReservationCommandService(
            ReservationRepository reservationRepository,
            ReservationDuplicateGuardPort duplicateGuardPort,
            StockCounterService stockCounterService,
            Clock clock
    ) {
        this.reservationRepository = reservationRepository;
        this.duplicateGuardPort    = duplicateGuardPort;
        this.stockCounterService   = stockCounterService;
        this.clock                 = clock;
    }

    public ReservationCreatedResult reserve(CreateReservationCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        // Step 1: idempotency check — return original result on replay
        Optional<Reservation> existing =
                reservationRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return new ReservationCreatedResult.IdempotentReplay(existing.get());
        }

        // Step 2: Redis NX duplicate guard (soft in-flight guard; DB index is the durable guard)
        try {
            boolean acquired = duplicateGuardPort.tryAcquire(command.userId(), command.saleId());
            if (!acquired) {
                return new ReservationCreatedResult.DuplicateReservation();
            }
        } catch (ReservationDuplicateGuardUnavailableException ex) {
            // ponytail: guard unavailable → fall through; DB partial unique index still active
            LOGGER.warn(
                    "Reservation duplicate guard unavailable for user {} and sale {}; "
                            + "continuing to database guard",
                    command.userId(),
                    command.saleId(),
                    ex
            );
        }

        // Step 3: stock decrement
        StockDecrementResult decrementResult = stockCounterService.decrement(
                command.productId(), command.saleId(), command.quantity().value());
        if (decrementResult instanceof StockDecrementResult.SoldOut) {
            return new ReservationCreatedResult.SoldOut();
        }
        StockDecrementResult.Decremented decremented =
                (StockDecrementResult.Decremented) decrementResult;

        // Step 4: create and persist reservation
        Instant now = clock.instant();
        Reservation reservation = Reservation.create(
                command.userId(),
                command.saleId(),
                command.productId(),
                command.quantity(),
                ReservationExpiry.in(RESERVATION_TTL, now),
                now,
                command.idempotencyKey()
        );
        InventoryEvent.StockReserved event = new InventoryEvent.StockReserved(
                java.util.UUID.randomUUID(),
                now,
                reservation.id(),
                reservation.saleId(),
                reservation.productId(),
                reservation.userId(),
                reservation.quantity().value(),
                decremented.remainingStock().value(),
                reservation.expiry().expiresAt()
        );
        return new ReservationCreatedResult.Created(
                reservationRepository.saveWithOutboxEvent(reservation, event));
    }
}
