package com.flashsale.sale.api;

import com.flashsale.sale.api.dto.ErrorResponse;
import com.flashsale.sale.application.SaleNotFoundException;
import com.flashsale.sale.domain.exception.SaleCreationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain/application exceptions into the HTTP responses PRD-FlashSalePlatform.md
 * specifies. Handlers are ordered most-specific first; Spring dispatches to the closest
 * matching type regardless of declaration order, but the ordering here mirrors that for
 * readability.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** EC-002 / EC-003 / EC-004 — creation-time validation failures with a specific error code. */
    @ExceptionHandler(SaleCreationException.class)
    public ResponseEntity<ErrorResponse> handleSaleCreation(SaleCreationException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ex.errorCode().name(), ex.getMessage()));
    }

    /** Structural validation failures (missing/blank required fields — FR-001). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("Request validation failed.");
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_ERROR", message));
    }

    /** GET /api/v1/sales/{id} for a sale that does not exist. */
    @ExceptionHandler(SaleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(SaleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("SALE_NOT_FOUND", ex.getMessage()));
    }

    /**
     * An illegal state-machine transition (Build-Plan.md Week 2 DoD: "Attempting
     * ENDED -&gt; ACTIVE throws and returns 409"). No Week 2 endpoint can currently trigger
     * this — it is exercised directly at the domain layer by
     * {@code FlashSaleStateMachineTest} — but the mapping is wired now so the future
     * {@code PATCH /api/v1/sales/{id}/status} endpoint (not built this week) gets correct
     * behaviour for free.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ILLEGAL_STATE_TRANSITION", ex.getMessage()));
    }

    /** Catch-all for malformed request values that aren't a SaleCreationException — e.g. a non-UUID productId path/body value. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", ex.getMessage()));
    }
}
