package com.flashsale.inventory.application;

import com.flashsale.inventory.application.port.ProductRepository;
import com.flashsale.inventory.application.port.StockPreWarmPort;
import com.flashsale.inventory.domain.aggregate.Product;
import com.flashsale.inventory.domain.entity.StockLevel;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.SaleId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pre-warms the Redis stock projection from an authoritative PostgreSQL snapshot.
 *
 * <p>Validates the pre-start timing window, loads Product-owned stock and revision from
 * PostgreSQL, derives expiration as {@code saleEnd + 10 minutes}, and delegates one atomic
 * revision-fenced initialization to the outbound pre-warm port.
 */
@Service
public class PreWarmStockUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreWarmStockUseCase.class);
    private static final Duration PRE_WARM_WINDOW = Duration.ofSeconds(60);
    private static final Duration PROJECTION_BUFFER = Duration.ofMinutes(10);

    private final ProductRepository productRepository;
    private final StockPreWarmPort stockPreWarmPort;
    private final Clock clock;

    public PreWarmStockUseCase(
            ProductRepository productRepository,
            StockPreWarmPort stockPreWarmPort,
            Clock clock
    ) {
        this.productRepository = productRepository;
        this.stockPreWarmPort = stockPreWarmPort;
        this.clock = clock;
    }

    public PreWarmStockResult preWarm(
            ProductId productId,
            SaleId saleId,
            Instant saleStart,
            Instant saleEnd
    ) {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(saleStart, "saleStart must not be null");
        Objects.requireNonNull(saleEnd, "saleEnd must not be null");
        if (!saleEnd.isAfter(saleStart)) {
            throw new IllegalArgumentException(
                    "saleEnd must be after saleStart: saleEnd=" + saleEnd + ", saleStart=" + saleStart);
        }

        Instant now = clock.instant();
        Instant preWarmAt = saleStart.minus(PRE_WARM_WINDOW);

        if (now.isBefore(preWarmAt)) {
            LOGGER.debug(
                    "Pre-warm not yet due for sale {}: now={}, preWarmAt={}",
                    saleId, now, preWarmAt
            );
            return PreWarmStockResult.NOT_DUE;
        }
        if (!now.isBefore(saleStart)) {
            LOGGER.warn(
                    "Pre-warm window missed for sale {}: now={}, saleStart={}",
                    saleId, now, saleStart
            );
            return PreWarmStockResult.MISSED_WINDOW;
        }

        Instant expiresAt = saleEnd.plus(PROJECTION_BUFFER);
        Duration ttl = Duration.between(now, expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            LOGGER.warn(
                    "Non-positive TTL for sale {}: expiresAt={}, now={}",
                    saleId, expiresAt, now
            );
            return PreWarmStockResult.MISSED_WINDOW;
        }

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + productId));

        StockLevel stockLevel = product.stockLevelFor(saleId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Product " + productId + " has no StockLevel for sale " + saleId
                ));

        PreWarmStockResult result = stockPreWarmPort.preWarm(
                saleId,
                stockLevel.currentStock(),
                stockLevel.version(),
                ttl
        );

        LOGGER.debug(
                "Pre-warm completed for sale {} with result {}: stock={}, revision={}",
                saleId, result, stockLevel.currentStock().value(), stockLevel.version()
        );

        return result;
    }
}
