package com.flashsale.inventory.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateReservationRequest(
        @NotBlank String userId,
        @NotBlank String saleId,
        @NotBlank String productId,
        @NotNull @Min(1) Integer quantity
) {}
