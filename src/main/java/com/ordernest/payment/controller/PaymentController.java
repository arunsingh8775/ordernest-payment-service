package com.ordernest.payment.controller;

import com.ordernest.payment.dto.ProcessPaymentRequest;
import com.ordernest.payment.dto.RazorpayCreateOrderResponse;
import com.ordernest.payment.dto.RazorpayVerifyPaymentRequest;
import com.ordernest.payment.dto.RazorpayVerifyPaymentResponse;
import com.ordernest.payment.service.PaymentProcessingService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentProcessingService paymentProcessingService;

    @PostMapping("/create-order")
    public ResponseEntity<RazorpayCreateOrderResponse> createOrder(
            @Valid @RequestBody ProcessPaymentRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        RazorpayCreateOrderResponse response = paymentProcessingService.createRazorpayOrder(request, authorization);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify")
    public ResponseEntity<RazorpayVerifyPaymentResponse> verifyPayment(
            @Valid @RequestBody RazorpayVerifyPaymentRequest request
    ) {
        RazorpayVerifyPaymentResponse response = paymentProcessingService.verifyPayment(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/webhooks/razorpay/refund")
    public ResponseEntity<Map<String, String>> handleRefundWebhook(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestBody String payload
    ) {
        paymentProcessingService.processRefundWebhook(payload, signature);
        return ResponseEntity.ok(Map.of("status", "acknowledged"));
    }
}
