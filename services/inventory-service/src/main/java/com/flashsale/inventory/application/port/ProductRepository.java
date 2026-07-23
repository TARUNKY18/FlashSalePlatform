package com.flashsale.inventory.application.port;

import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.vo.ProductId;
import java.util.Optional;

/**
 * Application boundary for loading and saving Product aggregates.
 */
public interface ProductRepository {

    Optional<Product> findById(ProductId productId);

    Product save(Product product);
}
