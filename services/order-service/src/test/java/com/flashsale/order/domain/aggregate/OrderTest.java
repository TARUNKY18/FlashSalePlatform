package com.flashsale.order.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flashsale.order.domain.vo.Money;
import com.flashsale.order.domain.vo.OrderId;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final Instant CREATED_AT = Instant.parse("2099-01-01T12:00:00Z");
    private static final Instant TRANSITIONED_AT = CREATED_AT.plusSeconds(60);
    private static final PurchaseIntentId PURCHASE_INTENT_ID =
            PurchaseIntentId.of(UUID.fromString("10000000-0000-0000-0000-000000000001"));
    private static final UserId USER_ID =
            UserId.of(UUID.fromString("20000000-0000-0000-0000-000000000002"));
    private static final SaleId SALE_ID =
            SaleId.of(UUID.fromString("30000000-0000-0000-0000-000000000003"));
    private static final Money AMOUNT = Money.of("19.99", "USD");
    private static final String IDEMPOTENCY_KEY = "opaque-key";

    private Order pending() {
        return Order.place(
                PURCHASE_INTENT_ID, USER_ID, SALE_ID, AMOUNT,
                IDEMPOTENCY_KEY, CREATED_AT
        );
    }

    @Test
    void placeCreatesPendingOrderWithRequiredState() {
        Order order = pending();

        assertAll(
                () -> assertNotNull(order.id()),
                () -> assertEquals(PURCHASE_INTENT_ID, order.purchaseIntentId()),
                () -> assertEquals(USER_ID, order.userId()),
                () -> assertEquals(SALE_ID, order.saleId()),
                () -> assertEquals(AMOUNT, order.amount()),
                () -> assertEquals(IDEMPOTENCY_KEY, order.idempotencyKey()),
                () -> assertEquals(CREATED_AT, order.createdAt()),
                () -> assertInstanceOf(Order.Status.Pending.class, order.status()),
                () -> assertEquals(0L, order.version())
        );
    }

    @Test
    void placeRejectsMissingRequiredState() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> Order.place(null, USER_ID, SALE_ID, AMOUNT,
                                IDEMPOTENCY_KEY, CREATED_AT)),
                () -> assertThrows(NullPointerException.class,
                        () -> Order.place(PURCHASE_INTENT_ID, null, SALE_ID, AMOUNT,
                                IDEMPOTENCY_KEY, CREATED_AT)),
                () -> assertThrows(NullPointerException.class,
                        () -> Order.place(PURCHASE_INTENT_ID, USER_ID, null, AMOUNT,
                                IDEMPOTENCY_KEY, CREATED_AT)),
                () -> assertThrows(NullPointerException.class,
                        () -> Order.place(PURCHASE_INTENT_ID, USER_ID, SALE_ID, null,
                                IDEMPOTENCY_KEY, CREATED_AT)),
                () -> assertThrows(NullPointerException.class,
                        () -> Order.place(PURCHASE_INTENT_ID, USER_ID, SALE_ID, AMOUNT,
                                null, CREATED_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Order.place(PURCHASE_INTENT_ID, USER_ID, SALE_ID, AMOUNT,
                                "  ", CREATED_AT)),
                () -> assertThrows(NullPointerException.class,
                        () -> Order.place(PURCHASE_INTENT_ID, USER_ID, SALE_ID, AMOUNT,
                                IDEMPOTENCY_KEY, null))
        );
    }

    @Test
    void confirmTransitionsPendingToConfirmed() {
        Order order = pending();

        order.confirm(TRANSITIONED_AT);

        Order.Status.Confirmed confirmed =
                assertInstanceOf(Order.Status.Confirmed.class, order.status());
        assertEquals(TRANSITIONED_AT, confirmed.confirmedAt());
        assertEquals(1L, order.version());
    }

    @Test
    void cancelTransitionsPendingToCancelled() {
        Order order = pending();

        order.cancel("PAYMENT_DECLINED", TRANSITIONED_AT);

        Order.Status.Cancelled cancelled =
                assertInstanceOf(Order.Status.Cancelled.class, order.status());
        assertEquals("PAYMENT_DECLINED", cancelled.reason());
        assertEquals(TRANSITIONED_AT, cancelled.cancelledAt());
        assertEquals(1L, order.version());
    }

    @Test
    void expireTransitionsPendingToExpired() {
        Order order = pending();

        order.expire(TRANSITIONED_AT);

        Order.Status.Expired expired =
                assertInstanceOf(Order.Status.Expired.class, order.status());
        assertEquals(TRANSITIONED_AT, expired.expiredAt());
        assertEquals(1L, order.version());
    }

    @Test
    void transitionsRejectInvalidArgumentsWithoutMutation() {
        Order order = pending();

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> order.confirm(null)),
                () -> assertThrows(NullPointerException.class,
                        () -> order.cancel(null, TRANSITIONED_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> order.cancel(" ", TRANSITIONED_AT)),
                () -> assertThrows(NullPointerException.class,
                        () -> order.cancel("REASON", null)),
                () -> assertThrows(NullPointerException.class, () -> order.expire(null))
        );
        assertInstanceOf(Order.Status.Pending.class, order.status());
        assertEquals(0L, order.version());
    }

    @Test
    void confirmedIsTerminal() {
        Order order = pending();
        order.confirm(TRANSITIONED_AT);

        assertTerminal(order);
    }

    @Test
    void cancelledIsTerminal() {
        Order order = pending();
        order.cancel("REASON", TRANSITIONED_AT);

        assertTerminal(order);
    }

    @Test
    void expiredIsTerminal() {
        Order order = pending();
        order.expire(TRANSITIONED_AT);

        assertTerminal(order);
    }

    @Test
    void moneyRequiresPositiveAmountAndCurrency() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new Money(null, Currency.getInstance("USD"))),
                () -> assertThrows(NullPointerException.class,
                        () -> new Money(BigDecimal.ONE, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Money(BigDecimal.ZERO, Currency.getInstance("USD"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Money(BigDecimal.valueOf(-1), Currency.getInstance("USD")))
        );
    }

    @Test
    void typedIdsRejectNullValues() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> OrderId.of((UUID) null)),
                () -> assertThrows(NullPointerException.class,
                        () -> PurchaseIntentId.of((UUID) null)),
                () -> assertThrows(NullPointerException.class, () -> UserId.of((UUID) null)),
                () -> assertThrows(NullPointerException.class, () -> SaleId.of((UUID) null))
        );
    }

    private void assertTerminal(Order order) {
        Order.Status terminalStatus = order.status();

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> order.confirm(TRANSITIONED_AT.plusSeconds(1))),
                () -> assertThrows(IllegalStateException.class,
                        () -> order.cancel("REASON", TRANSITIONED_AT.plusSeconds(1))),
                () -> assertThrows(IllegalStateException.class,
                        () -> order.expire(TRANSITIONED_AT.plusSeconds(1)))
        );
        assertEquals(terminalStatus, order.status());
        assertEquals(1L, order.version());
    }
}
