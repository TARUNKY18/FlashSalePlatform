package com.flashsale.order.api;

import com.flashsale.order.api.dto.ErrorResponse;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OrderExceptionHandler {

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        if (OrderController.IDEMPOTENCY_HEADER.equals(ex.getHeaderName())) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required."));
        }
        return invalidRequest(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return invalidRequest(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex) {
        return invalidRequest("Malformed JSON request.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidValue(IllegalArgumentException ex) {
        return invalidRequest(ex.getMessage());
    }

    @ExceptionHandler(OrderController.DuplicateReservationException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateReservation(
            OrderController.DuplicateReservationException ex
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "DUPLICATE_RESERVATION", "An order already exists for this reservation."));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class, IllegalStateException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseFailure(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                "DATABASE_ERROR", "Database operation failed."));
    }

    private static ResponseEntity<ErrorResponse> invalidRequest(String message) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", message));
    }
}
