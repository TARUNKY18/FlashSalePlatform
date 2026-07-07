package com.flashsale.sale.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.sale.api.dto.CreateSaleRequest;
import com.flashsale.sale.application.CreateSaleCommand;
import com.flashsale.sale.application.SaleCommandService;
import com.flashsale.sale.application.SaleNotFoundException;
import com.flashsale.sale.application.SaleQueryService;
import com.flashsale.sale.domain.aggregate.FlashSale;
import com.flashsale.sale.domain.vo.ProductId;
import com.flashsale.sale.domain.vo.SaleId;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Minimal slice test for the two Week 2 endpoints — not part of the required 8 domain
 * state-machine tests, added per the explicitly locked Week 2 decision to include
 * controller-level coverage alongside the domain tests.
 *
 * <p>{@code @WebMvcTest} loads only the web layer; {@code SaleCommandService} and
 * {@code SaleQueryService} are mocked, so this test needs no database.
 */
@WebMvcTest(SaleController.class)
class SaleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SaleCommandService saleCommandService;

    @MockBean
    private SaleQueryService saleQueryService;

    private static final ProductId PRODUCT_ID = ProductId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant SALE_START = NOW.plus(Duration.ofHours(1));
    private static final Instant SALE_END = NOW.plus(Duration.ofHours(2));

    private FlashSale sampleSale() {
        return FlashSale.schedule("Test Sale", PRODUCT_ID, 100, SALE_START, SALE_END, ZoneId.of("UTC"), NOW);
    }

    @Test
    void createSale_returns201WithSaleId() throws Exception {
        FlashSale sale = sampleSale();
        when(saleCommandService.createSale(any(CreateSaleCommand.class))).thenReturn(sale);

        CreateSaleRequest request = new CreateSaleRequest(
                "Test Sale", PRODUCT_ID.toString(), 100, SALE_START, SALE_END, "UTC"
        );

        mockMvc.perform(post("/api/v1/sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saleId", is(sale.id().toString())))
                .andExpect(jsonPath("$.status", is("SCHEDULED")));
    }

    @Test
    void createSale_missingName_returns400ValidationError() throws Exception {
        CreateSaleRequest request = new CreateSaleRequest(
                "", PRODUCT_ID.toString(), 100, SALE_START, SALE_END, "UTC"
        );

        mockMvc.perform(post("/api/v1/sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_ERROR")));
    }

    @Test
    void getSale_found_returns200() throws Exception {
        FlashSale sale = sampleSale();
        when(saleQueryService.getById(sale.id())).thenReturn(sale);

        mockMvc.perform(get("/api/v1/sales/{id}", sale.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleId", is(sale.id().toString())));
    }

    @Test
    void getSale_notFound_returns404() throws Exception {
        SaleId missingId = SaleId.generate();
        when(saleQueryService.getById(missingId)).thenThrow(new SaleNotFoundException(missingId));

        mockMvc.perform(get("/api/v1/sales/{id}", missingId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("SALE_NOT_FOUND")));
    }
}
