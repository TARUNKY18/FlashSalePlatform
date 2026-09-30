package com.flashsale.sale.infra.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

class KafkaTopicConfigurationTest {

    private final KafkaTopicConfiguration configuration = new KafkaTopicConfiguration();

    @Test
    void productionTopicHasFrozenConfiguration() {
        NewTopic topic = configuration.saleEventsTopic(3, 2);

        assertEquals("sale-events", topic.name());
        assertEquals(8, topic.numPartitions());
        assertEquals((short) 3, topic.replicationFactor());
        assertEquals("604800000", topic.configs().get(TopicConfig.RETENTION_MS_CONFIG));
        assertEquals("lz4", topic.configs().get(TopicConfig.COMPRESSION_TYPE_CONFIG));
        assertEquals("2", topic.configs().get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG));
    }

    @Test
    void singleBrokerTestEquivalentKeepsAllNonReplicationSettings() {
        NewTopic topic = configuration.saleEventsTopic(1, 1);

        assertEquals(8, topic.numPartitions());
        assertEquals((short) 1, topic.replicationFactor());
        assertEquals("1", topic.configs().get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG));
        assertEquals("604800000", topic.configs().get(TopicConfig.RETENTION_MS_CONFIG));
        assertEquals("lz4", topic.configs().get(TopicConfig.COMPRESSION_TYPE_CONFIG));
    }
}
