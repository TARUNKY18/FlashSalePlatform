package com.flashsale.inventory.infra.persistence;

import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.vo.ProductId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregate-oriented persistence adapter for Product.
 *
 * <p>StockLevels are persisted only through their owning Product. The adapter intentionally
 * exposes no child repository or independent StockLevel write path.
 */
@Repository
public class ProductRepository {

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
    public Optional<Product> findById(ProductId productId) {
        Objects.requireNonNull(productId, "productId must not be null");
        return springDataRepository.findById(productId.value())
                .map(mapper::toDomain);
    }

    /**
     * Saves the complete aggregate and returns the state after JPA has applied versioning.
     */
    @Transactional
    public Product save(Product product) {
        Objects.requireNonNull(product, "product must not be null");
        ProductJpaEntity saved = springDataRepository.saveAndFlush(
                mapper.toJpaEntity(product)
        );
        return mapper.toDomain(saved);
    }
}
