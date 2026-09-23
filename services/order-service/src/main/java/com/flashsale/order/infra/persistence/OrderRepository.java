package com.flashsale.order.infra.persistence;

import com.flashsale.order.domain.aggregate.Order;
import java.util.Objects;
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
}
