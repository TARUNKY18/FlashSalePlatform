package com.flashsale.inventory.infra.persistence;

import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.vo.ReservationId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregate-oriented persistence adapter for Reservation.
 */
@Repository
public class ReservationRepository
        implements com.flashsale.inventory.application.port.ReservationRepository {

    private final SpringDataReservationRepository springDataRepository;
    private final ReservationPersistenceMapper mapper;

    public ReservationRepository(
            SpringDataReservationRepository springDataRepository,
            ReservationPersistenceMapper mapper
    ) {
        this.springDataRepository = springDataRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<Reservation> findById(ReservationId id) {
        Objects.requireNonNull(id, "id must not be null");
        return springDataRepository.findById(id.value()).map(mapper::toDomain);
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
}
