package com.flashsale.sale.infra.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link Clock} used everywhere "now" matters (e.g. EC-002's
 * saleStart-must-be-future check in {@code FlashSale.schedule}). Injecting a {@code Clock}
 * bean rather than calling {@code Instant.now()} directly keeps that logic deterministic
 * and unit-testable without touching the wall clock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
