package com.flashsale.inventory.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.application.DurableStockDecrementResult;
import com.flashsale.inventory.application.port.DurableStockDecrementPort;
import com.flashsale.inventory.application.port.DurableStockUnavailableException;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import jakarta.persistence.PersistenceException;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

class PostgresStockDecrementAdapterTest {

    private static final ProductId PRODUCT_ID = ProductId.of(
            UUID.fromString("7f9387a8-5761-4985-bd86-1c1dac4a79ce")
    );
    private static final SaleId SALE_ID = SaleId.of(
            UUID.fromString("3009393b-5e47-4eac-9562-70e2d76b908a")
    );

    private final TransactionalStockDecrement transactionalStockDecrement =
            mock(TransactionalStockDecrement.class);
    private final DurableStockDecrementPort adapter =
            new PostgresStockDecrementAdapter(transactionalStockDecrement);

    @Test
    void delegatesAndReturnsCommittedResult() {
        DurableStockDecrementResult expected =
                new DurableStockDecrementResult.Decremented(StockCount.of(7), 4L);
        when(transactionalStockDecrement.decrement(PRODUCT_ID, SALE_ID, 3))
                .thenReturn(expected);

        DurableStockDecrementResult result =
                adapter.decrement(PRODUCT_ID, SALE_ID, 3);

        assertSame(expected, result);
        verify(transactionalStockDecrement).decrement(PRODUCT_ID, SALE_ID, 3);
    }

    @ParameterizedTest
    @MethodSource("infrastructureFailures")
    void translatesDatabaseTransactionAndCommitFailures(RuntimeException failure) {
        when(transactionalStockDecrement.decrement(PRODUCT_ID, SALE_ID, 1))
                .thenThrow(failure);

        DurableStockUnavailableException exception = assertThrows(
                DurableStockUnavailableException.class,
                () -> adapter.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        assertSame(failure, exception.getCause());
    }

    @Test
    void preservesMissingProductSemantics() {
        NoSuchElementException failure = new NoSuchElementException("missing");
        when(transactionalStockDecrement.decrement(PRODUCT_ID, SALE_ID, 1))
                .thenThrow(failure);

        NoSuchElementException result = assertThrows(
                NoSuchElementException.class,
                () -> adapter.decrement(PRODUCT_ID, SALE_ID, 1)
        );

        assertSame(failure, result);
    }

    private static Stream<RuntimeException> infrastructureFailures() {
        return Stream.of(
                new DataAccessResourceFailureException("database unavailable"),
                new TransactionSystemException("commit failed"),
                new PersistenceException("persistence failed")
        );
    }
}
