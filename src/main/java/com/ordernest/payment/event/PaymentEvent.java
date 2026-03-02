package com.ordernest.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentEvent(
        UUID productId,
        Integer quantity,
        BigDecimal amount,
        String currency,
        PaymentEventType eventType,
        String orderId,
        String paymentId,
        String reason,
        Instant timestamp
) {
}
