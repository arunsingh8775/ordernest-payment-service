package com.ordernest.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderStatusChangedEvent(
        String orderId,
        UUID userId,
        String userEmail,
        UUID productId,
        String productName,
        Integer quantity,
        BigDecimal totalAmount,
        String currency,
        String previousStatus,
        String currentStatus,
        String paymentStatus,
        String shipmentStatus,
        String reason,
        Instant timestamp
) {
}
