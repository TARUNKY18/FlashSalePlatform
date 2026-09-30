package com.flashsale.order.infra.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.application.OrderCommandService;
import com.flashsale.order.domain.vo.PurchaseIntent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code inventory-events}. {@code StockReserved} is translated through the
 * ACL and recorded; other event types are logged and acknowledged. Malformed events
 * are terminal: logged and acknowledged. Processing failures are not acknowledged.
 */
@Component
@Profile("infrastructure")
public class InventoryEventConsumer {

    static final String TOPIC = "inventory-events";
    static final String GROUP_ID = "order-svc-reservation-consumer";
    private static final String STOCK_RESERVED = "StockReserved";
    private static final Logger LOGGER = LoggerFactory.getLogger(InventoryEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final InventoryEventTranslator translator;
    private final OrderCommandService orderCommandService;

    public InventoryEventConsumer(
            ObjectMapper objectMapper,
            InventoryEventTranslator translator,
            OrderCommandService orderCommandService
    ) {
        this.objectMapper = objectMapper;
        this.translator = translator;
        this.orderCommandService = orderCommandService;
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            autoStartup = "${order.inventory-consumer.auto-startup:true}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        PurchaseIntent intent;
        try {
            JsonNode envelope = objectMapper.readTree(record.value());
            String eventType = envelope.path("eventType").asText();
            if (!STOCK_RESERVED.equals(eventType)) {
                LOGGER.info("Ignoring inventory event type={} offset={}", eventType, record.offset());
                ack.acknowledge();
                return;
            }
            StockReservedPayload payload =
                    objectMapper.treeToValue(envelope.get("payload"), StockReservedPayload.class);
            intent = translator.translate(payload);
        } catch (JsonProcessingException | IllegalArgumentException terminal) {
            LOGGER.error("Terminal inventory event partition={} offset={}; acknowledged without processing",
                    record.partition(), record.offset(), terminal);
            ack.acknowledge();
            return;
        }

        orderCommandService.processReservationConfirmed(intent);
        ack.acknowledge();
    }
}
