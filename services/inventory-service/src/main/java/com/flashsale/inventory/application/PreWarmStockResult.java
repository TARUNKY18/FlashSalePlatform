package com.flashsale.inventory.application;

/**
 * Infrastructure-neutral outcome of a stock projection pre-warm attempt.
 */
public enum PreWarmStockResult {
    WARMED,
    UPDATED,
    ALREADY_CURRENT,
    STALE_IGNORED,
    NOT_DUE,
    MISSED_WINDOW,
    INVALID_STATE
}
