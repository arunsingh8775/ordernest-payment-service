package com.ordernest.payment.dto;

public record RazorpayCreateOrderResponse(
        String internalOrderId,
        String razorpayOrderId,
        String razorpayKeyId,
        Long amount,
        String currency
) {
}
