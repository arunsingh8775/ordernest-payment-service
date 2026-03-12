package com.ordernest.payment.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordernest.payment.event.OrderCancellationEvent;
import com.ordernest.payment.service.PaymentProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancellationEventListener {

    private final ObjectMapper objectMapper;
    private final PaymentProcessingService paymentProcessingService;

    @KafkaListener(
            topics = "${app.kafka.topic.order-cancelled-events}",
            groupId = "${app.kafka.consumer.order-cancelled-group-id}"
    )
    public void onOrderCancelledEvent(String payload) {
        try {
            OrderCancellationEvent event = objectMapper.readValue(payload, OrderCancellationEvent.class);
            paymentProcessingService.processOrderCancellationEvent(event);
        } catch (JsonProcessingException ex) {
            log.error("Failed to parse order cancellation event payload: {}", payload, ex);
        } catch (Exception ex) {
            log.error("Failed to process order cancellation event payload: {}", payload, ex);
        }
    }
}
