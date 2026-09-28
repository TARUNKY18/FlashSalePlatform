package com.flashsale.order.infra.persistence;

import com.flashsale.order.domain.aggregate.Order;
import com.flashsale.order.domain.vo.OrderId;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.UserId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA adapter owning the atomic Order and outbox transaction. */
@Repository
@Profile("infrastructure")
public class OrderRepository
        implements com.flashsale.order.application.port.OrderRepository {

    private final SpringDataOrderRepository orders;
    private final SpringDataOrderOutboxRepository outbox;
    private final OrderPersistenceMapper mapper;

    public OrderRepository(
            SpringDataOrderRepository orders,
            SpringDataOrderOutboxRepository outbox,
            OrderPersistenceMapper mapper
    ) {
        this.orders = orders;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void save(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        orders.saveAndFlush(mapper.toOrderEntity(order));
        outbox.saveAndFlush(mapper.toOutboxEntity(order));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderId> findByUserIdAndIdempotencyKey(
            UserId userId,
            String idempotencyKey
    ) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        return orders.findByUserIdAndIdempotencyKey(userId.value(), idempotencyKey)
                .map(entity -> OrderId.of(entity.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByPurchaseIntentId(PurchaseIntentId purchaseIntentId) {
        Objects.requireNonNull(purchaseIntentId, "purchaseIntentId must not be null");
        return orders.existsByReservationId(purchaseIntentId.value());
    }
}
