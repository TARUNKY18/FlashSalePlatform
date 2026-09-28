package com.flashsale.order.application.port;

import com.flashsale.order.domain.aggregate.Order;
import com.flashsale.order.domain.vo.OrderId;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.UserId;
import java.util.Optional;

/** Persists an Order and its owned outbox event atomically. */
public interface OrderRepository {

    void save(Order order);

    Optional<OrderId> findByUserIdAndIdempotencyKey(UserId userId, String idempotencyKey);

    boolean existsByPurchaseIntentId(PurchaseIntentId purchaseIntentId);
}
