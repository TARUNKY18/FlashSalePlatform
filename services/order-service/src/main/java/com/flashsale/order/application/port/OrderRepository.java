package com.flashsale.order.application.port;

import com.flashsale.order.domain.aggregate.Order;

/** Persists an Order and its owned outbox event atomically. */
public interface OrderRepository {

    void save(Order order);
}
