package com.flashsale.order.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@Profile("infrastructure")
public class KafkaConsumerConfiguration {

    /**
     * Blocking in-place retry, one attempt per second, never skipping the record.
     * Replaces Spring Kafka's default (ten attempts, then skip), which would lose a
     * purchase intent during a database outage. Terminal events never reach this
     * handler: the listener acknowledges them itself.
     */
    // ponytail: unlimited blocking retry stalls the partition on a permanent failure; Week 8 retry/DLQ replaces it
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
