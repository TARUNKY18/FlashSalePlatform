package com.flashsale.inventory.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import com.flashsale.inventory.domain.vo.StockLevelId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class ProductStockCorrectnessPropertyTest {

    private static final ProductId PRODUCT_ID = ProductId.of(
            UUID.fromString("13bd8d97-c9f3-4e36-860f-2a826b16e2c8")
    );
    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("baf6921c-e034-44b4-b1e6-5f881156df43")
    );
    private static final StockLevelId STOCK_LEVEL_ID = StockLevelId.of(
            UUID.fromString("90363b14-15c2-4f74-8f5a-36c01bd7967a")
    );

    @Property(tries = 1_000)
    void successfulDecrementIsExactAndNeverNegative(
            @ForAll("successfulDecrements") DecrementCase decrementCase
    ) {
        Product product = productWithCurrentStock(decrementCase.startingStock());
        StockLevel before = stockLevel(product);

        Optional<StockCount> result = product.decrementStock(
                SALE_ID,
                decrementCase.quantity()
        );

        int expected = Math.toIntExact(
                (long) decrementCase.startingStock() - decrementCase.quantity()
        );
        assertEquals(Optional.of(StockCount.of(expected)), result);
        StockLevel after = stockLevel(product);
        assertEquals(StockCount.of(expected), after.currentStock());
        assertTrue(after.currentStock().value() >= 0);
        assertTrue(after.currentStock().value() <= decrementCase.startingStock());
        assertEquals(before.totalAllocated(), after.totalAllocated());
        assertEquals(before.version() + 1L, after.version());
        assertEquals(0L, product.version());
    }

    @Property(tries = 1_000)
    void insufficientDecrementLeavesOwnedStockUnchanged(
            @ForAll("insufficientDecrements") DecrementCase decrementCase
    ) {
        Product product = productWithCurrentStock(decrementCase.startingStock());
        StockLevel before = stockLevel(product);

        Optional<StockCount> result = product.decrementStock(
                SALE_ID,
                decrementCase.quantity()
        );

        assertTrue(result.isEmpty());
        StockLevel after = stockLevel(product);
        assertEquals(before.currentStock(), after.currentStock());
        assertEquals(before.totalAllocated(), after.totalAllocated());
        assertEquals(before.version(), after.version());
        assertEquals(0L, product.version());
    }

    @Property(tries = 1_000)
    void exactDepletionProducesValidZeroStock(
            @ForAll("positiveStocks") int startingStock
    ) {
        Product product = productWithCurrentStock(startingStock);

        Optional<StockCount> result = product.decrementStock(SALE_ID, startingStock);

        assertEquals(Optional.of(StockCount.zero()), result);
        StockLevel after = stockLevel(product);
        assertEquals(StockCount.zero(), after.currentStock());
        assertTrue(after.currentStock().isSoldOut());
        assertEquals(1L, after.version());
        assertEquals(0L, product.version());
    }

    @Property(tries = 1_000)
    void repeatedOperationsPreserveTheStockArithmeticModel(
            @ForAll("stockScenarios") StockScenario scenario
    ) {
        Product product = productWithCurrentStock(scenario.startingStock());
        long expectedStock = scenario.startingStock();
        long acceptedQuantity = 0L;
        long expectedStockLevelVersion = 0L;

        for (int quantity : scenario.quantities()) {
            StockLevel before = stockLevel(product);
            Optional<StockCount> result = product.decrementStock(SALE_ID, quantity);

            if (quantity <= expectedStock) {
                expectedStock -= quantity;
                acceptedQuantity += quantity;
                expectedStockLevelVersion++;
                assertEquals(
                        Optional.of(StockCount.of(Math.toIntExact(expectedStock))),
                        result
                );
            } else {
                assertTrue(result.isEmpty());
                assertEquals(before.currentStock(), stockLevel(product).currentStock());
                assertEquals(before.version(), stockLevel(product).version());
            }

            StockLevel after = stockLevel(product);
            assertEquals(Math.toIntExact(expectedStock), after.currentStock().value());
            assertTrue(after.currentStock().value() >= 0);
            assertEquals(expectedStockLevelVersion, after.version());
            assertEquals(0L, product.version());
        }

        assertEquals((long) scenario.startingStock() - acceptedQuantity, expectedStock);
        assertTrue(acceptedQuantity <= scenario.startingStock());
    }

    @Property(tries = 1_000)
    void operationsAfterExactDepletionRemainInsufficient(
            @ForAll("positiveStocks") int startingStock,
            @ForAll("positiveQuantities") int laterQuantity
    ) {
        Product product = productWithCurrentStock(startingStock);
        Optional<StockCount> depletion = product.decrementStock(SALE_ID, startingStock);
        StockLevel depleted = stockLevel(product);

        Optional<StockCount> laterResult = product.decrementStock(SALE_ID, laterQuantity);

        assertEquals(Optional.of(StockCount.zero()), depletion);
        assertTrue(laterResult.isEmpty());
        StockLevel after = stockLevel(product);
        assertEquals(StockCount.zero(), after.currentStock());
        assertEquals(depleted.version(), after.version());
        assertEquals(0L, product.version());
    }

    @Provide
    Arbitrary<Integer> positiveStocks() {
        return Arbitraries.integers().between(1, Integer.MAX_VALUE);
    }

    @Provide
    Arbitrary<Integer> positiveQuantities() {
        return Arbitraries.integers().between(1, Integer.MAX_VALUE);
    }

    @Provide
    Arbitrary<Integer> nonNegativeStocks() {
        return Arbitraries.integers().between(0, Integer.MAX_VALUE);
    }

    @Provide
    Arbitrary<DecrementCase> successfulDecrements() {
        return positiveStocks().flatMap(startingStock ->
                Arbitraries.integers()
                        .between(1, startingStock)
                        .map(quantity -> new DecrementCase(startingStock, quantity))
        );
    }

    @Provide
    Arbitrary<DecrementCase> insufficientDecrements() {
        return Arbitraries.integers()
                .between(0, Integer.MAX_VALUE - 1)
                .flatMap(startingStock -> Arbitraries.integers()
                        .between(startingStock + 1, Integer.MAX_VALUE)
                        .map(quantity -> new DecrementCase(startingStock, quantity))
                );
    }

    @Provide
    Arbitrary<StockScenario> stockScenarios() {
        return nonNegativeStocks().flatMap(startingStock ->
                positiveQuantities()
                        .list()
                        .ofMinSize(1)
                        .ofMaxSize(32)
                        .map(quantities -> new StockScenario(startingStock, quantities))
        );
    }

    private Product productWithCurrentStock(int currentStock) {
        int totalAllocated = Math.max(1, currentStock);
        StockLevel stockLevel = StockLevel.reconstitute(
                STOCK_LEVEL_ID,
                PRODUCT_ID,
                SALE_ID,
                StockCount.of(totalAllocated),
                StockCount.of(currentStock),
                0L
        );
        return Product.reconstitute(
                PRODUCT_ID,
                StockCount.of(totalAllocated),
                List.of(stockLevel),
                0L
        );
    }

    private StockLevel stockLevel(Product product) {
        return product.stockLevelFor(SALE_ID).orElseThrow();
    }

    private record DecrementCase(int startingStock, int quantity) {
    }

    private record StockScenario(int startingStock, List<Integer> quantities) {
    }
}
