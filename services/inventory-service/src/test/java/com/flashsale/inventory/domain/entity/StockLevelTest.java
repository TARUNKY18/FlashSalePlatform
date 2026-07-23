package com.flashsale.inventory.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import com.flashsale.inventory.domain.vo.StockLevelId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockLevelTest {

    private static final ProductId PRODUCT_ID = ProductId.of(
            UUID.fromString("ee73ecab-bfd0-4b01-ac3a-af355d4455ed")
    );
    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("a1fe36b6-2639-48bd-a5ed-46dc93c5969a")
    );

    @Test
    void allocationStartsFullyAvailableAtVersionZero() {
        StockLevel stockLevel = StockLevel.allocate(PRODUCT_ID, SALE_ID, StockCount.of(25));

        assertEquals(PRODUCT_ID, stockLevel.productId());
        assertEquals(SALE_ID, stockLevel.saleId());
        assertEquals(StockCount.of(25), stockLevel.totalAllocated());
        assertEquals(StockCount.of(25), stockLevel.currentStock());
        assertEquals(0L, stockLevel.version());
    }

    @Test
    void rejectsZeroAllocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StockLevel.allocate(PRODUCT_ID, SALE_ID, StockCount.zero())
        );
    }

    @Test
    void acceptsReconstitutedStockBetweenZeroAndAllocation() {
        StockLevel stockLevel = StockLevel.reconstitute(
                StockLevelId.generate(),
                PRODUCT_ID,
                SALE_ID,
                StockCount.of(25),
                StockCount.of(7),
                3L
        );

        assertEquals(StockCount.of(7), stockLevel.currentStock());
        assertEquals(3L, stockLevel.version());
    }

    @Test
    void acceptsSoldOutReconstitutedStock() {
        StockLevel stockLevel = StockLevel.reconstitute(
                StockLevelId.generate(),
                PRODUCT_ID,
                SALE_ID,
                StockCount.of(25),
                StockCount.zero(),
                25L
        );

        assertEquals(StockCount.zero(), stockLevel.currentStock());
    }

    @Test
    void rejectsCurrentStockAboveAllocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StockLevel.reconstitute(
                        StockLevelId.generate(),
                        PRODUCT_ID,
                        SALE_ID,
                        StockCount.of(25),
                        StockCount.of(26),
                        0L
                )
        );
    }

    @Test
    void rejectsNegativeVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StockLevel.reconstitute(
                        StockLevelId.generate(),
                        PRODUCT_ID,
                        SALE_ID,
                        StockCount.of(25),
                        StockCount.of(25),
                        -1L
                )
        );
    }
}
