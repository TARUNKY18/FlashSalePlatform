package com.flashsale.order.infra.persistence;

import com.flashsale.order.domain.vo.PurchaseIntent;
import java.time.Instant;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for received purchase intents. */
@Repository
@Profile("infrastructure")
public class PurchaseIntentRepository
        implements com.flashsale.order.application.port.PurchaseIntentRepository {

    private final SpringDataPurchaseIntentRepository springDataRepository;

    public PurchaseIntentRepository(SpringDataPurchaseIntentRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    @Transactional
    public boolean saveIfAbsent(PurchaseIntent intent, Instant receivedAt) {
        Objects.requireNonNull(intent, "intent must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        return springDataRepository.insertIfAbsent(
                intent.purchaseIntentId().value(),
                intent.userId().value(),
                intent.saleId().value(),
                intent.quantity(),
                intent.validUntil(),
                receivedAt
        ) == 1;
    }
}
