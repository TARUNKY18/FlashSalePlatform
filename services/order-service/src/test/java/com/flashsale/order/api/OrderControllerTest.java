package com.flashsale.order.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flashsale.order.application.IdempotencyService;
import com.flashsale.order.application.OrderCommandService;
import com.flashsale.order.application.OrderCommandService.PlacementResult;
import com.flashsale.order.domain.entity.IdempotencyRecord;
import com.flashsale.order.domain.vo.OrderId;
import com.flashsale.order.domain.vo.UserId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@ActiveProfiles("infrastructure")
class OrderControllerTest {

    private static final String KEY = "order-key";
    private static final UUID USER_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ORDER_ID =
            UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String REQUEST = """
            {
              "reservationId":"30000000-0000-0000-0000-000000000003",
              "userId":"10000000-0000-0000-0000-000000000001",
              "saleId":"40000000-0000-0000-0000-000000000004",
              "amount":100.00,
              "currency":"USD"
            }
            """;

    @Autowired private MockMvc mockMvc;
    @MockBean private IdempotencyService idempotencyService;
    @MockBean private OrderCommandService commandService;

    @Test
    void missingIdempotencyKeyIsRejectedBeforeBusinessLogic() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"error":"MISSING_IDEMPOTENCY_KEY",
                         "message":"Idempotency-Key header is required."}
                        """));

        verifyNoInteractions(idempotencyService, commandService);
    }

    @Test
    void invalidRequestReturnsFrozenError() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header(OrderController.IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.replace("\"currency\":\"USD\"", "\"currency\":\"\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("currency")))
                .andExpect(jsonPath("$.message").value(containsString("must not be blank")));

        verifyNoInteractions(idempotencyService, commandService);
    }

    @Test
    void malformedJsonReturnsFrozenError() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .header(OrderController.IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"error":"INVALID_REQUEST","message":"Malformed JSON request."}
                        """));
    }

    @Test
    void newOrderSerializesOnceAndPersistsExactResponse() throws Exception {
        UserId userId = UserId.of(USER_ID);
        when(idempotencyService.find(userId, KEY)).thenReturn(Optional.empty());
        when(commandService.place(any()))
                .thenReturn(new PlacementResult.Accepted(OrderId.of(ORDER_ID)));
        when(idempotencyService.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String expected = "{\"orderId\":\"" + ORDER_ID + "\",\"status\":\"PENDING\"}";
        mockMvc.perform(post("/api/v1/orders")
                        .header(OrderController.IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(content().string(expected));

        verify(idempotencyService).save(new IdempotencyRecord(userId, KEY, expected, 202));
    }

    @Test
    void idempotencyHitReturnsStoredStatusAndByteEquivalentBody() throws Exception {
        String stored = "{ \"status\" : \"PENDING\", \"orderId\" : \"original\" }";
        when(idempotencyService.find(UserId.of(USER_ID), KEY)).thenReturn(Optional.of(
                new IdempotencyRecord(UserId.of(USER_ID), KEY, stored, 202)));

        mockMvc.perform(post("/api/v1/orders")
                        .header(OrderController.IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(content().string(stored));

        verifyNoInteractions(commandService);
        verify(idempotencyService, never()).save(any());
    }

    @Test
    void duplicateReservationReturnsFrozenConflict() throws Exception {
        when(idempotencyService.find(UserId.of(USER_ID), KEY)).thenReturn(Optional.empty());
        when(commandService.place(any())).thenReturn(new PlacementResult.DuplicateReservation());

        mockMvc.perform(post("/api/v1/orders")
                        .header(OrderController.IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"error":"DUPLICATE_RESERVATION",
                         "message":"An order already exists for this reservation."}
                        """));
    }

    @Test
    void postgresFailureReturnsFrozenDatabaseError() throws Exception {
        when(idempotencyService.find(UserId.of(USER_ID), KEY))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        mockMvc.perform(post("/api/v1/orders")
                        .header(OrderController.IDEMPOTENCY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                        {"error":"DATABASE_ERROR","message":"Database operation failed."}
                        """));
    }
}
