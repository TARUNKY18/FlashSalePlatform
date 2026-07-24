package com.flashsale.inventory.infra.persistence;

import com.flashsale.inventory.application.port.StockFallbackPort;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL implementation of the durable fallback decrement.
 *
 * <p>The Product row is pessimistically locked for the full read, domain mutation, managed
 * StockLevel update, and flush transaction. Locking the aggregate root serializes all
 * fallback decrements for its owned StockLevels without exposing a child repository.
 */
@Repository
public class PostgresStockFallbackAdapter implements StockFallbackPort {

    private final SpringDataProductRepository springDataRepository;
    private final ProductPersistenceMapper mapper;

    public PostgresStockFallbackAdapter(
            SpringDataProductRepository springDataRepository,
            ProductPersistenceMapper mapper
    ) {
        this.springDataRepository = springDataRepository;
        this.mapper = mapper;
    }

    @Transactional
    @Override
    public Optional<StockCount> decrement(
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
        Optional<StockCount> remainingStock = product.decrementStock(saleId, quantity);
        if (remainingStock.isEmpty()) {
            return Optional.empty();
        }

        mapper.applyCurrentStock(product, lockedProduct, saleId);
        springDataRepository.flush();
        return remainingStock;
    }
}
