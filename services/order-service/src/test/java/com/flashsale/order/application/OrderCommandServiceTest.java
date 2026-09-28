package com.flashsale.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.order.application.OrderCommandService.PlacementResult;
import com.flashsale.order.application.port.OrderRepository;
import com.flashsale.order.domain.aggregate.Order;
import com.flashsale.order.domain.vo.Money;
import com.flashsale.order.domain.vo.OrderId;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class OrderCommandServiceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderCommandService service = new OrderCommandService(repository);
    private final PlaceOrderCommand command = new PlaceOrderCommand(
            PurchaseIntentId.of(UUID.fromString("10000000-0000-0000-0000-000000000001")),
            UserId.of(UUID.fromString("20000000-0000-0000-0000-000000000002")),
            SaleId.of(UUID.fromString("30000000-0000-0000-0000-000000000003")),
            Money.of("100.00", "USD"),
            "order-key"
    );

    @Test
    void crashGapReturnsPersistedOrderWithoutWritingAnother() {
        OrderId existing = OrderId.of(UUID.randomUUID());
        when(repository.findByUserIdAndIdempotencyKey(command.userId(), command.idempotencyKey()))
                .thenReturn(Optional.of(existing));

        PlacementResult.Accepted result = assertInstanceOf(
                PlacementResult.Accepted.class, service.place(command));

        assertEquals(existing, result.orderId());
        verify(repository, never()).save(any());
    }

    @Test
    void newRequestPlacesOrderThroughExistingRepositoryTransaction() {
        when(repository.findByUserIdAndIdempotencyKey(command.userId(), command.idempotencyKey()))
                .thenReturn(Optional.empty());

        assertInstanceOf(PlacementResult.Accepted.class, service.place(command));

        verify(repository).save(any(Order.class));
    }

    @Test
    void sameKeyUniquenessLoserRecoversCommittedOrder() {
        OrderId winner = OrderId.of(UUID.randomUUID());
        when(repository.findByUserIdAndIdempotencyKey(command.userId(), command.idempotencyKey()))
                .thenReturn(Optional.empty(), Optional.of(winner));
        DataIntegrityViolationException conflict =
                new DataIntegrityViolationException("same key");
        org.mockito.Mockito.doThrow(conflict).when(repository).save(any(Order.class));

        PlacementResult.Accepted result = assertInstanceOf(
                PlacementResult.Accepted.class, service.place(command));

        assertEquals(winner, result.orderId());
        verify(repository, never()).existsByPurchaseIntentId(any());
    }

    @Test
    void provenReservationCollisionReturnsDuplicateResult() {
        when(repository.findByUserIdAndIdempotencyKey(command.userId(), command.idempotencyKey()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("reservation"))
                .when(repository).save(any(Order.class));
        when(repository.existsByPurchaseIntentId(command.purchaseIntentId())).thenReturn(true);

        assertInstanceOf(PlacementResult.DuplicateReservation.class, service.place(command));
    }

    @Test
    void unprovenIntegrityFailureRemainsDatabaseFailure() {
        when(repository.findByUserIdAndIdempotencyKey(command.userId(), command.idempotencyKey()))
                .thenReturn(Optional.empty());
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unexpected constraint");
        org.mockito.Mockito.doThrow(failure).when(repository).save(any(Order.class));
        when(repository.existsByPurchaseIntentId(command.purchaseIntentId())).thenReturn(false);

        assertSame(failure, assertThrows(
                DataIntegrityViolationException.class, () -> service.place(command)));
    }
}
