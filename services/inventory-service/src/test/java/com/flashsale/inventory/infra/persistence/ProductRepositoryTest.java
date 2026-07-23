package com.flashsale.inventory.infra.persistence;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.StockCount;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductRepositoryTest {

    private static final UUID PRODUCT_UUID =
            UUID.fromString("23b8db1b-5400-43a0-b029-f4a9c5f90e21");
    private static final ProductId PRODUCT_ID = ProductId.of(PRODUCT_UUID);

    private final SpringDataProductRepository springDataRepository =
            mock(SpringDataProductRepository.class);
    private final ProductPersistenceMapper mapper = mock(ProductPersistenceMapper.class);
    private final ProductRepository repository =
            new ProductRepository(springDataRepository, mapper);

    @Test
    void loadsAndMapsCompleteAggregate() {
        ProductJpaEntity entity = new ProductJpaEntity(PRODUCT_UUID, 100, 0L);
        Product aggregate = Product.create(PRODUCT_ID, StockCount.of(100));
        when(springDataRepository.findById(PRODUCT_UUID)).thenReturn(Optional.of(entity));
        when(mapper.toDomain(entity)).thenReturn(aggregate);

        Product loaded = repository.findById(PRODUCT_ID).orElseThrow();

        assertSame(aggregate, loaded);
        verify(springDataRepository).findById(PRODUCT_UUID);
        verify(mapper).toDomain(entity);
    }

    @Test
    void returnsEmptyWhenProductDoesNotExist() {
        when(springDataRepository.findById(PRODUCT_UUID)).thenReturn(Optional.empty());

        Optional<Product> result = repository.findById(PRODUCT_ID);

        assertTrue(result.isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void mapsSavesFlushesAndReturnsPersistedAggregate() {
        Product aggregate = Product.create(PRODUCT_ID, StockCount.of(100));
        Product persistedAggregate = Product.reconstitute(
                PRODUCT_ID,
                StockCount.of(100),
                aggregate.stockLevels(),
                1L
        );
        ProductJpaEntity entity = new ProductJpaEntity(PRODUCT_UUID, 100, 0L);
        ProductJpaEntity savedEntity = new ProductJpaEntity(PRODUCT_UUID, 100, 1L);
        when(mapper.toJpaEntity(aggregate)).thenReturn(entity);
        when(springDataRepository.saveAndFlush(entity)).thenReturn(savedEntity);
        when(mapper.toDomain(savedEntity)).thenReturn(persistedAggregate);

        Product saved = repository.save(aggregate);

        assertSame(persistedAggregate, saved);
        verify(mapper).toJpaEntity(aggregate);
        verify(springDataRepository).saveAndFlush(entity);
        verify(mapper).toDomain(savedEntity);
    }
}
