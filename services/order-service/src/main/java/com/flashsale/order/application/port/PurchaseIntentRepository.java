package com.flashsale.order.application.port;

import com.flashsale.order.domain.vo.PurchaseIntent;
import java.time.Instant;

/** Durable record of purchase intents received from InventoryContext. */
public interface PurchaseIntentRepository {

    /** Inserts the intent unless one with the same id exists; returns true when inserted. */
    boolean saveIfAbsent(PurchaseIntent intent, Instant receivedAt);
}
