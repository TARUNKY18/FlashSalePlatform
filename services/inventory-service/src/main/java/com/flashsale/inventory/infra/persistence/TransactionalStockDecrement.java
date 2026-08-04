package com.flashsale.inventory.infra.persistence;

import com.flashsale.inventory.application.DurableStockDecrementResult;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the complete authoritative PostgreSQL decrement transaction.
 */
@Component
public class TransactionalStockDecrement {

    private final SpringDataProductRepository springDataRepository;
    private final ProductPersistenceMapper mapper;

    public TransactionalStockDecrement(
            SpringDataProductRepository springDataRepository,
            ProductPersistenceMapper mapper
    ) {
        this.springDataRepository = springDataRepository;
        this.mapper = mapper;
    }

    @Transactional
    public DurableStockDecrementResult decrement(
            ProductId productId,
            SaleId saleId,
            int quantity
    ) {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(saleId, "saleId must not be null");

        ProductJpaEntity lockedProduct = springDataRepository
                .findByIdForUpdate(productId.value())
                .orElseThrow(() -> new NoSuchElementException(
                        "Product not found: " + productId
                ));
        Product product = mapper.toDomain(lockedProduct);
        StockLevel lockedStockLevel = product.stockLevelFor(saleId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Product " + productId
                                + " has no StockLevel for sale " + saleId
                ));

        Optional<StockCount> remainingStock = product.decrementStock(saleId, quantity);
        if (remainingStock.isEmpty()) {
            return new DurableStockDecrementResult.Insufficient(
                    lockedStockLevel.currentStock(),
                    lockedStockLevel.version()
            );
        }

        StockLevelJpaEntity managedStockLevel =
                mapper.applyCurrentStock(product, lockedProduct, saleId);
        springDataRepository.flush();
        return new DurableStockDecrementResult.Decremented(
                StockCount.of(managedStockLevel.getCurrentStock()),
                managedStockLevel.getVersion()
        );
    }
}
