package com.flashsale.order.infra.config;

import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
public class KafkaTopicConfiguration {

    @Bean
    NewTopic orderEventsTopic(
            @Value("${order.kafka.topic.replication-factor:3}") int replicationFactor,
            @Value("${order.kafka.topic.min-in-sync-replicas:2}") int minInSyncReplicas
    ) {
        return TopicBuilder.name("order-events")
                .partitions(8)
                .replicas(replicationFactor)
                .configs(Map.of(
                        TopicConfig.RETENTION_MS_CONFIG, "259200000",
                        TopicConfig.COMPRESSION_TYPE_CONFIG, "lz4",
                        TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG,
                        Integer.toString(minInSyncReplicas)
                ))
                .build();
    }
}
