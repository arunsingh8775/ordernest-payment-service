package com.ordernest.payment.dto;

public record RazorpayVerifyPaymentResponse(
        String orderId,
        String paymentId,
        boolean verified,
        String message
) {
}
