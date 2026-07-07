package com.flashsale.sale.api.dto;

/**
 * Error body shape, matching the <code>{ "error": "...", "message": "..." }</code> format
 * used throughout PRD-FlashSalePlatform.md §7 (e.g. EC-002, EC-003, EC-004).
 */
public record ErrorResponse(String error, String message) {
}
