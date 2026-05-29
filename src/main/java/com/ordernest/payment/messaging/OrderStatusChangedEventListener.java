package com.ordernest.payment.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordernest.payment.event.OrderCancellationEvent;
import com.ordernest.payment.event.OrderCancellationEventType;
import com.ordernest.payment.event.OrderStatusChangedEvent;
import com.ordernest.payment.service.PaymentProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusChangedEventListener {

    private static final String CANCELLED = "CANCELLED";
    private static final String REFUND_INITIATED = "REFUND_INITIATED";

    private final ObjectMapper objectMapper;
    private final PaymentProcessingService paymentProcessingService;

    @KafkaListener(
            topics = "${app.kafka.topic.order-status-events:order.status.events}",
            groupId = "${app.kafka.consumer.order-status-group-id:ordernest-payment-service-order-status-consumer}"
    )
    public void onOrderStatusChangedEvent(String payload) {
        final OrderStatusChangedEvent event;
        try {
            event = objectMapper.readValue(payload, OrderStatusChangedEvent.class);
        } catch (JsonProcessingException ex) {
            log.error("Failed to parse order status changed event payload: {}", payload, ex);
            return;
        }

        if (!shouldInitiateRefund(event)) {
            return;
        }

        paymentProcessingService.processOrderCancellationEvent(new OrderCancellationEvent(
                event.productId(),
                event.quantity(),
                event.orderId(),
                OrderCancellationEventType.CANCELLED,
                event.reason(),
                event.timestamp()
        ));
    }

    private boolean shouldInitiateRefund(OrderStatusChangedEvent event) {
        if (event == null) {
            return false;
        }
        if (!CANCELLED.equalsIgnoreCase(event.currentStatus())) {
            return false;
        }
        if (CANCELLED.equalsIgnoreCase(event.previousStatus())) {
            return false;
        }
        return REFUND_INITIATED.equalsIgnoreCase(event.paymentStatus());
    }
}
