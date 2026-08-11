package com.flashsale.inventory.application.port;

import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;

/**
 * Outbound port for the short-lived Redis NX guard that prevents duplicate
 * in-flight reservation creation for the same user and sale.
 */
public interface ReservationDuplicateGuardPort {

    /**
     * Attempts to acquire the duplicate-creation guard for the given user and sale.
     *
     * @return {@code true} if the lock was acquired (not a duplicate in-flight request);
     *         {@code false} if the lock already exists (duplicate in-flight request)
     * @throws ReservationDuplicateGuardUnavailableException if the guard outcome is indeterminate
     */
    boolean tryAcquire(UserId userId, SaleId saleId);
}
