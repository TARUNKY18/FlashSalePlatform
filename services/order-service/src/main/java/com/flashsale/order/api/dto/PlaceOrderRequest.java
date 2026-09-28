package com.flashsale.order.api.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record PlaceOrderRequest(
        @NotBlank String reservationId,
        @NotBlank String userId,
        @NotBlank String saleId,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency
) {}
