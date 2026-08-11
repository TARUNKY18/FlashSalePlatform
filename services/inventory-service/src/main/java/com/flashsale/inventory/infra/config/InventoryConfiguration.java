package com.flashsale.inventory.infra.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class InventoryConfiguration {

    @Bean
    public Clock systemUtcClock() {
        return Clock.systemUTC();
    }
}
