package com.flashsale.inventory.infra.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flashsale.inventory.application.InventoryEvent;
import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.vo.ReservationId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregate-oriented persistence adapter for Reservation.
 */
@Repository
public class ReservationRepository
        implements com.flashsale.inventory.application.port.ReservationRepository {

    private final SpringDataReservationRepository springDataRepository;
    private final SpringDataInventoryOutboxRepository outboxRepository;
    private final ReservationPersistenceMapper mapper;
    private final ObjectMapper objectMapper;

    public ReservationRepository(
            SpringDataReservationRepository springDataRepository,
            SpringDataInventoryOutboxRepository outboxRepository,
            ReservationPersistenceMapper mapper,
            ObjectMapper objectMapper
    ) {
        this.springDataRepository = springDataRepository;
        this.outboxRepository = outboxRepository;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<Reservation> findById(ReservationId id) {
        Objects.requireNonNull(id, "id must not be null");
        return springDataRepository.findById(id.value()).map(mapper::toDomain);
    }

    @Transactional(readOnly = true)
    @Override
    public List<Reservation> findExpiredPending(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return springDataRepository.findExpiredPending(now)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<Reservation> findByIdempotencyKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return springDataRepository.findByIdempotencyKey(key).map(mapper::toDomain);
    }

    /**
     * Saves the aggregate and returns the state after JPA has applied versioning.
     * For existing aggregates, loads the managed entity and updates mutable fields
     * in-place so Hibernate's @Version optimistic lock check uses the current DB version.
     */
    @Transactional
    @Override
    public Reservation save(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        return saveReservation(reservation);
    }

    @Transactional
    @Override
    public Reservation saveWithOutboxEvent(Reservation reservation, InventoryEvent event) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        Objects.requireNonNull(event, "event must not be null");
        if (!reservation.id().equals(event.reservationId())
                || !reservation.id().equals(event.aggregateId())) {
            throw new IllegalArgumentException("event must belong to the saved reservation");
        }

        Reservation saved = saveReservation(reservation);
        InventoryOutboxJpaEntity outbox = new InventoryOutboxJpaEntity(
                UUID.randomUUID(),
                reservation.id().value(),
                event.eventId(),
                event.eventType(),
                event.eventVersion(),
                event.aggregateId().value(),
                event.aggregateType(),
                payload(event),
                event.occurredAt()
        );
        outboxRepository.saveAndFlush(outbox);
        return saved;
    }

    private Reservation saveReservation(Reservation reservation) {
        ReservationJpaEntity toSave = springDataRepository
                .findById(reservation.id().value())
                .map(existing -> {
                    existing.updateStatus(
                            reservation.status().getClass().getSimpleName().toUpperCase());
                    existing.updateOrderId(
                            reservation.orderId() != null ? reservation.orderId().value() : null);
                    return existing;
                })
                .orElseGet(() -> mapper.toJpaEntity(reservation));
        return mapper.toDomain(springDataRepository.saveAndFlush(toSave));
    }

    private JsonNode payload(InventoryEvent event) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("reservationId", event.reservationId().value().toString());
        if (event instanceof InventoryEvent.StockReserved stockReserved) {
            payload.put("saleId", stockReserved.saleId().value().toString());
            payload.put("productId", stockReserved.productId().value().toString());
            payload.put("userId", stockReserved.userId().value().toString());
            payload.put("quantity", stockReserved.quantity());
            payload.put("remainingStock", stockReserved.remainingStock());
            payload.put("expiresAt", stockReserved.expiresAt().toString());
        } else if (event instanceof InventoryEvent.ReservationExpired reservationExpired) {
            payload.put("saleId", reservationExpired.saleId().value().toString());
            payload.put("productId", reservationExpired.productId().value().toString());
            payload.put("userId", reservationExpired.userId().value().toString());
            payload.put("quantity", reservationExpired.quantity());
            payload.put("expiredAt", reservationExpired.expiredAt().toString());
        } else {
            throw new IllegalArgumentException("unsupported inventory event type");
        }
        return payload;
    }
}
