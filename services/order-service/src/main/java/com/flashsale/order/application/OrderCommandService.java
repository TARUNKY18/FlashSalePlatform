package com.flashsale.order.application;

import com.flashsale.order.application.port.OrderRepository;
import com.flashsale.order.domain.aggregate.Order;
import com.flashsale.order.domain.vo.OrderId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Places an Order or recovers the Order that already owns its durable identity. */
@Service
@Profile("infrastructure")
public class OrderCommandService {

    private final OrderRepository repository;

    public OrderCommandService(OrderRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public PlacementResult place(PlaceOrderCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Optional<OrderId> existing = findExisting(command);
        if (existing.isPresent()) {
            return new PlacementResult.Accepted(existing.get());
        }

        Order order = Order.place(
                command.purchaseIntentId(),
                command.userId(),
                command.saleId(),
                command.amount(),
                command.idempotencyKey(),
                Instant.now()
        );

        try {
            repository.save(order);
            return new PlacementResult.Accepted(order.id());
        } catch (DataIntegrityViolationException failure) {
            existing = findExisting(command);
            if (existing.isPresent()) {
                return new PlacementResult.Accepted(existing.get());
            }
            if (repository.existsByPurchaseIntentId(command.purchaseIntentId())) {
                return new PlacementResult.DuplicateReservation();
            }
            throw failure;
        }
    }

    private Optional<OrderId> findExisting(PlaceOrderCommand command) {
        return repository.findByUserIdAndIdempotencyKey(
                command.userId(), command.idempotencyKey());
    }

    public sealed interface PlacementResult
            permits PlacementResult.Accepted, PlacementResult.DuplicateReservation {

        record Accepted(OrderId orderId) implements PlacementResult {
            public Accepted {
                Objects.requireNonNull(orderId, "orderId must not be null");
            }
        }

        record DuplicateReservation() implements PlacementResult {}
    }
}
