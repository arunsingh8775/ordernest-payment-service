package com.ordernest.payment.service;

import com.ordernest.payment.client.OrderClient;
import com.ordernest.payment.client.OrderDetailsResponse;
import com.ordernest.payment.dto.ProcessPaymentRequest;
import com.ordernest.payment.event.PaymentEvent;
import com.ordernest.payment.event.PaymentEventType;
import com.ordernest.payment.messaging.PaymentEventPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentProcessingService {

    private final PaymentEventPublisher paymentEventPublisher;
    private final OrderClient orderClient;

    public void process(ProcessPaymentRequest request, String authorization) {
        UUID orderId = parseOrderId(request.orderId());
        OrderDetailsResponse order = orderClient.getOrderById(orderId, authorization);
        BigDecimal amount = order.item().totalAmount();
        String currency = order.item().currency();

        int draw = ThreadLocalRandom.current().nextInt(1, 11);
        boolean success = draw <= 7;
        String paymentId = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();

        PaymentEventType eventType = success ? PaymentEventType.PAYMENT_SUCCESS : PaymentEventType.PAYMENT_FAILED;
        String reason = success ? null : "Randomized failure, draw=" + draw;

        PaymentEvent event = new PaymentEvent(
                order.item().productId(),
                order.item().quantity(),
                amount,
                currency,
                eventType,
                request.orderId(),
                paymentId,
                reason,
                timestamp
        );
        paymentEventPublisher.publish(event);
    }

    private UUID parseOrderId(String rawOrderId) {
        try {
            return UUID.fromString(rawOrderId);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid orderId");
        }
    }
}
