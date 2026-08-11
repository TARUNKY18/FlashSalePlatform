package com.flashsale.inventory.api;

import com.flashsale.inventory.api.dto.CreateReservationRequest;
import com.flashsale.inventory.api.dto.ReservationResponse;
import com.flashsale.inventory.application.CreateReservationCommand;
import com.flashsale.inventory.application.ReservationCommandService;
import com.flashsale.inventory.application.ReservationCreatedResult;
import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private final ReservationCommandService commandService;

    public ReservationController(ReservationCommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> create(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request
    ) {
        CreateReservationCommand command = new CreateReservationCommand(
                idempotencyKey,
                new UserId(UUID.fromString(request.userId())),
                new SaleId(UUID.fromString(request.saleId())),
                new ProductId(UUID.fromString(request.productId())),
                Quantity.of(request.quantity())
        );

        ReservationCreatedResult result = commandService.reserve(command);

        return switch (result) {
            case ReservationCreatedResult.Created c -> {
                URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(c.reservation().id().value())
                        .toUri();
                yield ResponseEntity.created(location).body(toResponse(c.reservation()));
            }
            case ReservationCreatedResult.IdempotentReplay r ->
                    ResponseEntity.ok(toResponse(r.reservation()));
            case ReservationCreatedResult.SoldOut ignored ->
                    throw new SoldOutException();
            case ReservationCreatedResult.DuplicateReservation ignored ->
                    throw new DuplicateReservationException();
        };
    }

    private static ReservationResponse toResponse(Reservation r) {
        return new ReservationResponse(
                r.id().value(),
                r.userId().value(),
                r.saleId().value(),
                r.productId().value(),
                r.quantity().value(),
                r.status().getClass().getSimpleName().toUpperCase(),
                r.expiry().expiresAt()
        );
    }

    static final class SoldOutException extends RuntimeException {
        SoldOutException() { super("Stock sold out"); }
    }

    static final class DuplicateReservationException extends RuntimeException {
        DuplicateReservationException() { super("Duplicate reservation detected"); }
    }
}
