package com.ordernest.payment.service;

import com.ordernest.payment.client.OrderClient;
import com.ordernest.payment.client.OrderDetailsResponse;
import com.ordernest.payment.dto.ProcessPaymentRequest;
import com.ordernest.payment.dto.RazorpayCreateOrderResponse;
import com.ordernest.payment.dto.RazorpayVerifyPaymentRequest;
import com.ordernest.payment.dto.RazorpayVerifyPaymentResponse;
import com.ordernest.payment.event.PaymentEvent;
import com.ordernest.payment.event.PaymentEventType;
import com.ordernest.payment.messaging.PaymentEventPublisher;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class PaymentProcessingService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final PaymentEventPublisher paymentEventPublisher;
    private final OrderClient orderClient;
    private final RestClient razorpayClient;
    private final String razorpayKeyId;
    private final String razorpayKeySecret;
    private final Map<UUID, PendingPaymentAttempt> pendingAttempts = new ConcurrentHashMap<>();

    public PaymentProcessingService(
            PaymentEventPublisher paymentEventPublisher,
            OrderClient orderClient,
            @org.springframework.beans.factory.annotation.Value("${app.razorpay.base-url:https://api.razorpay.com}") String razorpayBaseUrl,
            @org.springframework.beans.factory.annotation.Value("${app.razorpay.key-id:}") String razorpayKeyId,
            @org.springframework.beans.factory.annotation.Value("${app.razorpay.key-secret:}") String razorpayKeySecret
    ) {
        this.paymentEventPublisher = paymentEventPublisher;
        this.orderClient = orderClient;
        this.razorpayClient = RestClient.builder().baseUrl(razorpayBaseUrl).build();
        this.razorpayKeyId = razorpayKeyId;
        this.razorpayKeySecret = razorpayKeySecret;
    }

    public RazorpayCreateOrderResponse createRazorpayOrder(ProcessPaymentRequest request, String authorization) {
        validateRazorpayConfiguration();

        UUID orderId = parseOrderId(request.orderId());
        OrderDetailsResponse order = orderClient.getOrderById(orderId, authorization);

        BigDecimal amount = order.item().totalAmount();
        String currency = order.item().currency();

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Order amount must be greater than zero");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Order currency is required");
        }

        long amountInSubunits = toSubunits(amount);
        RazorpayOrderResponse razorpayOrder = createRazorpayOrder(amountInSubunits, currency, request.orderId());

        pendingAttempts.put(orderId, new PendingPaymentAttempt(
                request.orderId(),
                razorpayOrder.id(),
                order.item().productId(),
                order.item().quantity(),
                amount,
                currency,
                Instant.now()
        ));

        clearStaleAttempts();

        return new RazorpayCreateOrderResponse(
                request.orderId(),
                razorpayOrder.id(),
                razorpayKeyId,
                amountInSubunits,
                currency
        );
    }

    public RazorpayVerifyPaymentResponse verifyPayment(RazorpayVerifyPaymentRequest request) {
        validateRazorpayConfiguration();

        UUID orderId = parseOrderId(request.orderId());
        PendingPaymentAttempt pendingAttempt = pendingAttempts.remove(orderId);
        if (pendingAttempt == null) {
            throw new IllegalArgumentException("No pending payment attempt found for orderId");
        }

        if (!pendingAttempt.razorpayOrderId().equals(request.razorpayOrderId())) {
            publishFailureEvent(pendingAttempt, request.razorpayPaymentId(), "Mismatched razorpayOrderId");
            return new RazorpayVerifyPaymentResponse(
                    pendingAttempt.internalOrderId(),
                    request.razorpayPaymentId(),
                    false,
                    "Payment verification failed"
            );
        }

        String payload = request.razorpayOrderId() + "|" + request.razorpayPaymentId();
        String expectedSignature = hmacSha256(payload, razorpayKeySecret);
        boolean verified = MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                request.razorpaySignature().getBytes(StandardCharsets.UTF_8)
        );

        if (verified) {
            publishSuccessEvent(pendingAttempt, request.razorpayPaymentId());
            return new RazorpayVerifyPaymentResponse(
                    pendingAttempt.internalOrderId(),
                    request.razorpayPaymentId(),
                    true,
                    "Payment verified"
            );
        }

        publishFailureEvent(pendingAttempt, request.razorpayPaymentId(), "Invalid Razorpay signature");
        return new RazorpayVerifyPaymentResponse(
                pendingAttempt.internalOrderId(),
                request.razorpayPaymentId(),
                false,
                "Payment verification failed"
        );
    }

    private UUID parseOrderId(String rawOrderId) {
        try {
            return UUID.fromString(rawOrderId);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid orderId");
        }
    }

    private void validateRazorpayConfiguration() {
        if (razorpayKeyId == null || razorpayKeyId.isBlank() || razorpayKeySecret == null || razorpayKeySecret.isBlank()) {
            throw new IllegalStateException("Razorpay keys are not configured");
        }
    }

    private RazorpayOrderResponse createRazorpayOrder(long amountInSubunits, String currency, String receipt) {
        String basicAuth = Base64.getEncoder()
                .encodeToString((razorpayKeyId + ":" + razorpayKeySecret).getBytes(StandardCharsets.UTF_8));

        RazorpayOrderRequest body = new RazorpayOrderRequest(amountInSubunits, currency, receipt, Map.of("source", "ordernest"));

        RazorpayOrderResponse response = razorpayClient.post()
                .uri("/v1/orders")
                .header("Authorization", "Basic " + basicAuth)
                .body(body)
                .retrieve()
                .body(RazorpayOrderResponse.class);

        if (response == null || response.id() == null || response.id().isBlank()) {
            throw new IllegalStateException("Unable to create Razorpay order");
        }
        return response;
    }

    private long toSubunits(BigDecimal amount) {
        try {
            return amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Invalid amount for payment processing");
        }
    }

    private String hmacSha256(String payload, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to verify payment signature");
        }
    }

    private void publishSuccessEvent(PendingPaymentAttempt attempt, String paymentId) {
        paymentEventPublisher.publish(new PaymentEvent(
                attempt.productId(),
                attempt.quantity(),
                attempt.amount(),
                attempt.currency(),
                PaymentEventType.PAYMENT_SUCCESS,
                attempt.internalOrderId(),
                paymentId,
                null,
                Instant.now()
        ));
    }

    private void publishFailureEvent(PendingPaymentAttempt attempt, String paymentId, String reason) {
        paymentEventPublisher.publish(new PaymentEvent(
                attempt.productId(),
                attempt.quantity(),
                attempt.amount(),
                attempt.currency(),
                PaymentEventType.PAYMENT_FAILED,
                attempt.internalOrderId(),
                paymentId,
                reason,
                Instant.now()
        ));
    }

    private void clearStaleAttempts() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        pendingAttempts.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }

    private record RazorpayOrderRequest(
            Long amount,
            String currency,
            String receipt,
            Map<String, String> notes
    ) {
    }

    private record RazorpayOrderResponse(
            String id,
            String entity,
            String status
    ) {
    }

    private record PendingPaymentAttempt(
            String internalOrderId,
            String razorpayOrderId,
            UUID productId,
            Integer quantity,
            BigDecimal amount,
            String currency,
            Instant createdAt
    ) {
    }
}
