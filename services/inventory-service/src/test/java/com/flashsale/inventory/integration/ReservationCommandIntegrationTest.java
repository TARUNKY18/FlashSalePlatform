package com.flashsale.inventory.integration;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.flashsale.inventory.application.CreateReservationCommand;
import com.flashsale.inventory.application.ReservationCommandService;
import com.flashsale.inventory.application.ReservationCreatedResult;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationCommandIntegrationTest extends InventoryInfrastructureTestSupport {

    private static final UUID PRODUCT_UUID    = UUID.fromString("aabb1100-0000-0000-0000-000000000001");
    private static final UUID STOCK_LEVEL_UUID = UUID.fromString("aabb1100-0000-0000-0000-000000000002");
    private static final UUID SALE_UUID        = UUID.fromString("aabb1100-0000-0000-0000-000000000003");

    private static final ProductId PRODUCT_ID = ProductId.of(PRODUCT_UUID);
    private static final SaleId    SALE_ID    = SaleId.of(SALE_UUID);

    @Autowired
    private ReservationCommandService commandService;

    @BeforeEach
    void insertStock() {
        insertProductWithStock(PRODUCT_UUID, STOCK_LEVEL_UUID, SALE_UUID,
                100, 0L, 100, 100, 0L);
    }

    @Test
    void firstCommandCreatesReservation() {
        CreateReservationCommand command = command(UUID.randomUUID(), SALE_UUID, UUID.randomUUID().toString());

        ReservationCreatedResult result = commandService.reserve(command);

        assertInstanceOf(ReservationCreatedResult.Created.class, result);
    }

    @Test
    void sameIdempotencyKeyReturnsIdempotentReplay() {
        String iKey = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        CreateReservationCommand cmd = command(userId, SALE_UUID, iKey);

        ReservationCreatedResult first  = commandService.reserve(cmd);
        ReservationCreatedResult second = commandService.reserve(cmd);

        assertInstanceOf(ReservationCreatedResult.Created.class, first);
        assertInstanceOf(ReservationCreatedResult.IdempotentReplay.class, second);
    }

    @Test
    void differentUsersForSameSaleBothSucceed() {
        ReservationCreatedResult r1 = commandService.reserve(
                command(UUID.randomUUID(), SALE_UUID, UUID.randomUUID().toString()));
        ReservationCreatedResult r2 = commandService.reserve(
                command(UUID.randomUUID(), SALE_UUID, UUID.randomUUID().toString()));

        assertInstanceOf(ReservationCreatedResult.Created.class, r1);
        assertInstanceOf(ReservationCreatedResult.Created.class, r2);
    }

    @Test
    void v3NotNullEnforcedBySuccessfulInsertWithIdempotencyKey() {
        // V3 migration makes idempotency_key NOT NULL; if service passed null the insert would fail
        ReservationCreatedResult result = commandService.reserve(
                command(UUID.randomUUID(), SALE_UUID, UUID.randomUUID().toString()));
        assertInstanceOf(ReservationCreatedResult.Created.class, result);
    }

    private CreateReservationCommand command(UUID userId, UUID saleId, String iKey) {
        return new CreateReservationCommand(
                iKey,
                UserId.of(userId),
                SaleId.of(saleId),
                PRODUCT_ID,
                Quantity.one()
        );
    }
}
