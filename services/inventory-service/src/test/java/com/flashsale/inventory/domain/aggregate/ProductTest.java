package com.flashsale.inventory.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import com.flashsale.inventory.domain.vo.StockLevelId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {

    private static final ProductId PRODUCT_ID = ProductId.of(
            UUID.fromString("14dbff13-6a6f-4e79-9961-19c8ed4d1f90")
    );
    private static final SaleId FIRST_SALE_ID = SaleId.of(
            UUID.fromString("776376a1-cad0-42d3-94b4-dd08ce86989f")
    );
    private static final SaleId SECOND_SALE_ID = SaleId.of(
            UUID.fromString("92643e82-779c-47d6-ab75-6b3a0dd7a1fc")
    );

    @Test
    void allowsProductWithZeroTotalStock() {
        Product product = Product.create(PRODUCT_ID, StockCount.zero());

        assertEquals(StockCount.zero(), product.totalStock());
        assertEquals(StockCount.zero(), product.allocatedStock());
        assertEquals(StockCount.zero(), product.availableToAllocate());
        assertTrue(product.stockLevels().isEmpty());
    }

    @Test
    void allocatesStockAndOwnsTheNewStockLevel() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));

        StockLevel stockLevel = product.allocateStock(FIRST_SALE_ID, StockCount.of(40));

        assertEquals(PRODUCT_ID, stockLevel.productId());
        assertEquals(FIRST_SALE_ID, stockLevel.saleId());
        assertEquals(StockCount.of(40), stockLevel.totalAllocated());
        assertEquals(StockCount.of(40), product.allocatedStock());
        assertEquals(StockCount.of(60), product.availableToAllocate());
        assertEquals(stockLevel, product.stockLevelFor(FIRST_SALE_ID).orElseThrow());
        assertEquals(1L, product.version());
    }

    @Test
    void permitsDistinctSalesUpToExactTotalStock() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));

        product.allocateStock(FIRST_SALE_ID, StockCount.of(40));
        product.allocateStock(SECOND_SALE_ID, StockCount.of(60));

        assertEquals(2, product.stockLevels().size());
        assertEquals(StockCount.of(100), product.allocatedStock());
        assertEquals(StockCount.zero(), product.availableToAllocate());
        assertEquals(2L, product.version());
    }

    @Test
    void rejectsZeroAllocationWithoutChangingAggregate() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));

        assertThrows(
                IllegalArgumentException.class,
                () -> product.allocateStock(FIRST_SALE_ID, StockCount.zero())
        );

        assertTrue(product.stockLevels().isEmpty());
        assertEquals(0L, product.version());
    }

    @Test
    void rejectsAllocationBeyondTotalStockWithoutChangingAggregate() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        product.allocateStock(FIRST_SALE_ID, StockCount.of(70));

        assertThrows(
                IllegalStateException.class,
                () -> product.allocateStock(SECOND_SALE_ID, StockCount.of(31))
        );

        assertFalse(product.stockLevelFor(SECOND_SALE_ID).isPresent());
        assertEquals(StockCount.of(70), product.allocatedStock());
        assertEquals(1L, product.version());
    }

    @Test
    void rejectsSecondStockLevelForSameSale() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        product.allocateStock(FIRST_SALE_ID, StockCount.of(40));

        assertThrows(
                IllegalStateException.class,
                () -> product.allocateStock(FIRST_SALE_ID, StockCount.of(10))
        );

        assertEquals(1, product.stockLevels().size());
        assertEquals(StockCount.of(40), product.allocatedStock());
        assertEquals(1L, product.version());
    }

    @Test
    void exposesAnUnmodifiableStockLevelSnapshot() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        product.allocateStock(FIRST_SALE_ID, StockCount.of(40));
        List<StockLevel> snapshot = product.stockLevels();

        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertEquals(1, product.stockLevels().size());
    }

    @Test
    void rejectsReconstitutionWithStockLevelOwnedByAnotherProduct() {
        StockLevel foreignStockLevel = StockLevel.allocate(
                ProductId.generate(),
                FIRST_SALE_ID,
                StockCount.of(10)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> Product.reconstitute(
                        PRODUCT_ID,
                        StockCount.of(100),
                        List.of(foreignStockLevel),
                        0L
                )
        );
    }

    @Test
    void rejectsReconstitutionWithDuplicateSaleAllocations() {
        StockLevel first = StockLevel.allocate(
                PRODUCT_ID,
                FIRST_SALE_ID,
                StockCount.of(10)
        );
        StockLevel duplicate = StockLevel.allocate(
                PRODUCT_ID,
                FIRST_SALE_ID,
                StockCount.of(20)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> Product.reconstitute(
                        PRODUCT_ID,
                        StockCount.of(100),
                        List.of(first, duplicate),
                        0L
                )
        );
    }

    @Test
    void rejectsReconstitutionWhenAllocationsExceedTotalStock() {
        StockLevel first = StockLevel.allocate(
                PRODUCT_ID,
                FIRST_SALE_ID,
                StockCount.of(60)
        );
        StockLevel second = StockLevel.allocate(
                PRODUCT_ID,
                SECOND_SALE_ID,
                StockCount.of(41)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> Product.reconstitute(
                        PRODUCT_ID,
                        StockCount.of(100),
                        List.of(first, second),
                        0L
                )
        );
    }

    @Test
    void decrementsOwnedStockAndAdvancesOnlyStockLevelVersion() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        product.allocateStock(FIRST_SALE_ID, StockCount.of(40));

        Optional<StockCount> result = product.decrementStock(FIRST_SALE_ID, 3);

        assertEquals(Optional.of(StockCount.of(37)), result);
        StockLevel stockLevel = product.stockLevelFor(FIRST_SALE_ID).orElseThrow();
        assertEquals(StockCount.of(37), stockLevel.currentStock());
        assertEquals(1L, stockLevel.version());
        assertEquals(1L, product.version());
    }

    @Test
    void insufficientCurrentStockDoesNotMutateOwnedStockLevel() {
        StockLevel stockLevel = StockLevel.reconstitute(
                StockLevelId.generate(),
                PRODUCT_ID,
                FIRST_SALE_ID,
                StockCount.of(40),
                StockCount.of(2),
                7L
        );
        Product product = Product.reconstitute(
                PRODUCT_ID,
                StockCount.of(100),
                List.of(stockLevel),
                3L
        );

        Optional<StockCount> result = product.decrementStock(FIRST_SALE_ID, 3);

        assertTrue(result.isEmpty());
        StockLevel unchanged = product.stockLevelFor(FIRST_SALE_ID).orElseThrow();
        assertEquals(StockCount.of(2), unchanged.currentStock());
        assertEquals(7L, unchanged.version());
        assertEquals(3L, product.version());
    }

    @Test
    void rejectsDecrementForMissingOwnedStockLevel() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));

        assertThrows(
                NoSuchElementException.class,
                () -> product.decrementStock(FIRST_SALE_ID, 1)
        );
    }

    @Test
    void rejectsNonPositiveDecrementWithoutMutation() {
        Product product = Product.create(PRODUCT_ID, StockCount.of(100));
        product.allocateStock(FIRST_SALE_ID, StockCount.of(40));

        assertThrows(
                IllegalArgumentException.class,
                () -> product.decrementStock(FIRST_SALE_ID, 0)
        );

        StockLevel unchanged = product.stockLevelFor(FIRST_SALE_ID).orElseThrow();
        assertEquals(StockCount.of(40), unchanged.currentStock());
        assertEquals(0L, unchanged.version());
    }
}
