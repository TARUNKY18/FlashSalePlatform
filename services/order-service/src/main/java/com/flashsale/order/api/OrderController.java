package com.flashsale.order.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.api.dto.OrderResponse;
import com.flashsale.order.api.dto.PlaceOrderRequest;
import com.flashsale.order.application.IdempotencyService;
import com.flashsale.order.application.OrderCommandService;
import com.flashsale.order.application.OrderCommandService.PlacementResult;
import com.flashsale.order.application.PlaceOrderCommand;
import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.Money;
import com.flashsale.order.domain.vo.PurchaseIntentId;
import com.flashsale.order.domain.vo.SaleId;
import com.flashsale.order.domain.vo.UserId;
import jakarta.validation.Valid;
import java.io.UncheckedIOException;
import java.util.Currency;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("infrastructure")
@RequestMapping("/api/v1/orders")
public class OrderController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String PENDING = "PENDING";

    private final IdempotencyService idempotencyService;
    private final OrderCommandService commandService;
    private final ObjectMapper objectMapper;

    public OrderController(
            IdempotencyService idempotencyService,
            OrderCommandService commandService,
            ObjectMapper objectMapper
    ) {
        this.idempotencyService = idempotencyService;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<String> place(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request
    ) {
        PlaceOrderCommand command = toCommand(request, idempotencyKey);
        Optional<IdempotencyRecord> replay = idempotencyService.find(
                command.userId(), command.idempotencyKey());
        if (replay.isPresent()) {
            return response(replay.get());
        }

        PlacementResult result = commandService.place(command);
        if (result instanceof PlacementResult.DuplicateReservation) {
            throw new DuplicateReservationException();
        }

        var accepted = (PlacementResult.Accepted) result;
        String payload = serialize(new OrderResponse(accepted.orderId().value(), PENDING));
        IdempotencyRecord stored = idempotencyService.save(new IdempotencyRecord(
                command.userId(), command.idempotencyKey(), payload, HttpStatus.ACCEPTED.value()));
        return response(stored);
    }

    private static PlaceOrderCommand toCommand(
            PlaceOrderRequest request,
            String idempotencyKey
    ) {
        return new PlaceOrderCommand(
                PurchaseIntentId.of(request.reservationId()),
                UserId.of(request.userId()),
                SaleId.of(request.saleId()),
                new Money(request.amount(), Currency.getInstance(request.currency())),
                idempotencyKey
        );
    }

    private String serialize(OrderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException("Failed to serialize order response", ex);
        }
    }

    private static ResponseEntity<String> response(IdempotencyRecord record) {
        return ResponseEntity.status(HttpStatusCode.valueOf(record.httpStatus()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(record.responsePayload());
    }

    static final class DuplicateReservationException extends RuntimeException {
        DuplicateReservationException() {
            super("An order already exists for this reservation.");
        }
    }
}
