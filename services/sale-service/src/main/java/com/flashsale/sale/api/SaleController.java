package com.flashsale.sale.api;

import com.flashsale.sale.api.dto.CreateSaleRequest;
import com.flashsale.sale.api.dto.SaleResponse;
import com.flashsale.sale.application.CreateSaleCommand;
import com.flashsale.sale.application.SaleCommandService;
import com.flashsale.sale.application.SaleQueryService;
import com.flashsale.sale.domain.aggregate.FlashSale;
import com.flashsale.sale.domain.vo.ProductId;
import com.flashsale.sale.domain.vo.SaleId;
import jakarta.validation.Valid;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SaleService's Week 2 REST surface: {@code POST /api/v1/sales} and
 * {@code GET /api/v1/sales/{id}} only (FR-001, US-001). No other endpoint from
 * README.md's SaleService table ({@code PATCH .../status}, {@code GET .../active},
 * {@code GET .../history}) is implemented yet — those are Weeks 3/7 per Build-Plan.md.
 */
@RestController
@RequestMapping("/api/v1/sales")
public class SaleController {

    private final SaleCommandService saleCommandService;
    private final SaleQueryService saleQueryService;

    public SaleController(SaleCommandService saleCommandService, SaleQueryService saleQueryService) {
        this.saleCommandService = saleCommandService;
        this.saleQueryService = saleQueryService;
    }

    @PostMapping
    public ResponseEntity<SaleResponse> createSale(@Valid @RequestBody CreateSaleRequest request) {
        ZoneId timezone = request.timezone() != null ? ZoneId.of(request.timezone()) : ZoneId.of("UTC");

        CreateSaleCommand command = new CreateSaleCommand(
                request.name(),
                ProductId.of(request.productId()),
                request.totalStock(),
                request.saleStart(),
                request.saleEnd(),
                timezone
        );

        FlashSale sale = saleCommandService.createSale(command);
        return ResponseEntity.status(HttpStatus.CREATED).body(SaleResponse.from(sale));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SaleResponse> getSale(@PathVariable String id) {
        FlashSale sale = saleQueryService.getById(SaleId.of(id));
        return ResponseEntity.ok(SaleResponse.from(sale));
    }
}
