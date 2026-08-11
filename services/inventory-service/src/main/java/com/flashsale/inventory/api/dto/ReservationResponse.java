package com.flashsale.inventory.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        UUID userId,
        UUID saleId,
        UUID productId,
        int quantity,
        String status,
        Instant expiresAt
) {}
