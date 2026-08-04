package com.flashsale.inventory.infra.persistence;

import com.flashsale.inventory.application.port.DurableStockUnavailableException;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.vo.ProductId;
import jakarta.persistence.PersistenceException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregate-oriented persistence adapter for Product.
 *
 * <p>StockLevels are persisted only through their owning Product. The adapter intentionally
 * exposes no child repository or independent StockLevel write path.
 */
@Repository
public class ProductRepository
        implements com.flashsale.inventory.application.port.ProductRepository {

    private final SpringDataProductRepository springDataRepository;
    private final ProductPersistenceMapper mapper;

    public ProductRepository(
            SpringDataProductRepository springDataRepository,
            ProductPersistenceMapper mapper
    ) {
        this.springDataRepository = springDataRepository;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<Product> findById(ProductId productId) {
        Objects.requireNonNull(productId, "productId must not be null");
        try {
            return springDataRepository.findById(productId.value())
                    .map(mapper::toDomain);
        } catch (DataAccessException | TransactionException | PersistenceException exception) {
            throw new DurableStockUnavailableException(
                    "Product validation read failed",
                    exception
            );
        }
    }

    /**
     * Saves the complete aggregate and returns the state after JPA has applied versioning.
     */
    @Transactional
    @Override
    public Product save(Product product) {
        Objects.requireNonNull(product, "product must not be null");
        ProductJpaEntity saved = springDataRepository.saveAndFlush(
                mapper.toJpaEntity(product)
        );
        return mapper.toDomain(saved);
    }
}
