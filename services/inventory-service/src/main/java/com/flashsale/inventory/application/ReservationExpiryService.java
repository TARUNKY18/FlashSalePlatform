package com.flashsale.inventory.application;

import com.flashsale.inventory.application.port.ProductRepository;
import com.flashsale.inventory.application.port.ReservationRepository;
import com.flashsale.inventory.application.port.StockReleasePort;
import com.flashsale.inventory.application.port.StockReleaseUnavailableException;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.entity.StockLevel;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Transitions PENDING reservations past their TTL to EXPIRED and restores Redis stock.
 *
 * <p>The PostgreSQL status update is authoritative. Redis stock restoration is best-effort:
 * a failed or missing Redis key is logged and does not abort the sweep.
 *
 * <p>ponytail: no LIMIT on the query; add batching if sweep latency becomes measurable.
 */
@Service
public class ReservationExpiryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReservationExpiryService.class);

    private final ReservationRepository reservationRepository;
    private final ProductRepository productRepository;
    private final StockReleasePort stockReleasePort;
    private final Clock clock;

    public ReservationExpiryService(
            ReservationRepository reservationRepository,
            ProductRepository productRepository,
            StockReleasePort stockReleasePort,
            Clock clock
    ) {
        this.reservationRepository = reservationRepository;
        this.productRepository     = productRepository;
        this.stockReleasePort      = stockReleasePort;
        this.clock                 = clock;
    }

    @Scheduled(fixedDelay = 30_000)
    public void expireReservations() {
        List<Reservation> expired = reservationRepository.findExpiredPending(clock.instant());
        if (expired.isEmpty()) {
            return;
        }
        LOGGER.debug("Expiry sweep found {} reservation(s) to expire", expired.size());

        for (Reservation reservation : expired) {
            try {
                expireOne(reservation);
            } catch (Exception ex) {
                LOGGER.error(
                        "Failed to expire reservation {}: {}",
                        reservation.id(), ex.getMessage(), ex
                );
            }
        }
    }

    private void expireOne(Reservation reservation) {
        reservation.expire();
        Instant occurredAt = clock.instant();
        InventoryEvent.ReservationExpired event = new InventoryEvent.ReservationExpired(
                UUID.randomUUID(),
                occurredAt,
                reservation.id(),
                reservation.saleId(),
                reservation.productId(),
                reservation.userId(),
                reservation.quantity().value(),
                reservation.expiry().expiresAt()
        );
        reservationRepository.saveWithOutboxEvent(reservation, event);

        releaseStock(reservation);
    }

    private void releaseStock(Reservation reservation) {
        Optional<Product> productOpt = productRepository.findById(reservation.productId());
        if (productOpt.isEmpty()) {
            LOGGER.warn(
                    "Product {} not found while releasing stock for reservation {}; "
                            + "Redis stock not restored",
                    reservation.productId(), reservation.id()
            );
            return;
        }

        Optional<StockLevel> levelOpt =
                productOpt.get().stockLevelFor(reservation.saleId());
        if (levelOpt.isEmpty()) {
            LOGGER.warn(
                    "StockLevel for sale {} not found on product {} for reservation {}; "
                            + "Redis stock not restored",
                    reservation.saleId(), reservation.productId(), reservation.id()
            );
            return;
        }

        int ceiling = levelOpt.get().totalAllocated().value();
        int qty     = reservation.quantity().value();

        try {
            StockReleaseResult result = stockReleasePort.release(
                    reservation.saleId(), qty, ceiling);
            if (result == StockReleaseResult.SALE_ENDED) {
                LOGGER.info(
                        "Redis stock key absent for sale {} (sale ended or key evicted); "
                                + "reservation {} expired in DB",
                        reservation.saleId(), reservation.id()
                );
            }
        } catch (StockReleaseUnavailableException ex) {
            LOGGER.warn(
                    "Redis stock release unavailable for reservation {}: {}",
                    reservation.id(), ex.getMessage(), ex
            );
        }
    }
}
