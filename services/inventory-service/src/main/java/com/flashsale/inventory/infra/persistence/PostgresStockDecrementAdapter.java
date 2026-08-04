package com.flashsale.inventory.infra.persistence;

import com.flashsale.inventory.application.DurableStockDecrementResult;
import com.flashsale.inventory.application.port.DurableStockDecrementPort;
import com.flashsale.inventory.application.port.DurableStockUnavailableException;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionException;

/**
 * PostgreSQL adapter whose separately proxied collaborator owns the durable transaction.
 */
@Repository
public class PostgresStockDecrementAdapter implements DurableStockDecrementPort {

    private final TransactionalStockDecrement transactionalStockDecrement;

    public PostgresStockDecrementAdapter(
            TransactionalStockDecrement transactionalStockDecrement
    ) {
        this.transactionalStockDecrement = transactionalStockDecrement;
    }

    @Override
    public DurableStockDecrementResult decrement(
            ProductId productId,
            SaleId saleId,
            int quantity
    ) {
        try {
            return transactionalStockDecrement.decrement(productId, saleId, quantity);
        } catch (DataAccessException | TransactionException | PersistenceException exception) {
            throw new DurableStockUnavailableException(
                    "Authoritative stock decrement failed",
                    exception
            );
        }
    }
}
