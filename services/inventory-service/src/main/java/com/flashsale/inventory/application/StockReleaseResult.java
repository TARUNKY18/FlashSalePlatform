package com.flashsale.inventory.application;

/**
 * Infrastructure-neutral outcome of a Redis stock release attempt.
 */
public enum StockReleaseResult {
    /** Stock was restored; new level returned by Lua. */
    RELEASED,
    /** The stock key was absent — sale has ended or key was evicted. */
    SALE_ENDED
}
