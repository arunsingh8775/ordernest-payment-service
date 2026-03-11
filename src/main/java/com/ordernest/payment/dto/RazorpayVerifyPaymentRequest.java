package com.ordernest.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record RazorpayVerifyPaymentRequest(
        @NotBlank(message = "orderId is required")
        String orderId,
        @NotBlank(message = "razorpayOrderId is required")
        String razorpayOrderId,
        @NotBlank(message = "razorpayPaymentId is required")
        String razorpayPaymentId,
        @NotBlank(message = "razorpaySignature is required")
        String razorpaySignature
) {
}
