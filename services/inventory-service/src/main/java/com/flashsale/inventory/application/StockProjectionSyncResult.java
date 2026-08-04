package com.flashsale.inventory.application;

/**
 * Infrastructure-neutral outcome of revision-fenced Redis projection synchronization.
 */
public enum StockProjectionSyncResult {
    APPLIED,
    STALE_IGNORED,
    MISSING
}
