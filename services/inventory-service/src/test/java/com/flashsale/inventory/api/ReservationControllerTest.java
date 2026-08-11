package com.flashsale.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.inventory.api.dto.CreateReservationRequest;
import com.flashsale.inventory.application.ReservationCommandService;
import com.flashsale.inventory.application.ReservationCreatedResult;
import com.flashsale.inventory.domain.aggregate.Reservation;
import com.flashsale.inventory.domain.vo.ProductId;
import com.flashsale.inventory.domain.vo.Quantity;
import com.flashsale.inventory.domain.vo.ReservationExpiry;
import com.flashsale.inventory.domain.vo.ReservationId;
import com.flashsale.inventory.domain.vo.SaleId;
import com.flashsale.inventory.domain.vo.UserId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    private static final UUID   USER_UUID    = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID   SALE_UUID    = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID   PRODUCT_UUID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String IKEY         = "test-idempotency-key";
    private static final Instant EXPIRES_AT  = Instant.parse("2099-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReservationCommandService commandService;

    @Test
    void postReturns201CreatedWithLocationHeader() throws Exception {
        when(commandService.reserve(any()))
                .thenReturn(new ReservationCreatedResult.Created(stubReservation()));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("X-Idempotency-Key", IKEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void idempotentReplayReturns200() throws Exception {
        when(commandService.reserve(any()))
                .thenReturn(new ReservationCreatedResult.IdempotentReplay(stubReservation()));

        mockMvc.perform(post("/api/v1/reservations")
                        .header("X-Idempotency-Key", IKEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").exists());
    }

    @Test
    void missingIdempotencyKeyHeaderReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_HEADER"));
    }

    @Test
    void invalidRequestBodyReturns400() throws Exception {
        CreateReservationRequest bad = new CreateReservationRequest(
                null, SALE_UUID.toString(), PRODUCT_UUID.toString(), 1
        );

        mockMvc.perform(post("/api/v1/reservations")
                        .header("X-Idempotency-Key", IKEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void soldOutReturns409WithSoldOutError() throws Exception {
        when(commandService.reserve(any()))
                .thenReturn(new ReservationCreatedResult.SoldOut());

        mockMvc.perform(post("/api/v1/reservations")
                        .header("X-Idempotency-Key", IKEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SOLD_OUT"));
    }

    @Test
    void duplicateReservationReturns409WithDuplicateError() throws Exception {
        when(commandService.reserve(any()))
                .thenReturn(new ReservationCreatedResult.DuplicateReservation());

        mockMvc.perform(post("/api/v1/reservations")
                        .header("X-Idempotency-Key", IKEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_RESERVATION"));
    }

    private CreateReservationRequest validRequest() {
        return new CreateReservationRequest(
                USER_UUID.toString(),
                SALE_UUID.toString(),
                PRODUCT_UUID.toString(),
                1
        );
    }

    private Reservation stubReservation() {
        return Reservation.reconstitute(
                ReservationId.generate(),
                UserId.of(USER_UUID),
                SaleId.of(SALE_UUID),
                ProductId.of(PRODUCT_UUID),
                Quantity.one(),
                new ReservationExpiry(EXPIRES_AT),
                new Reservation.Status.Pending(),
                null,
                0L,
                IKEY
        );
    }
}
