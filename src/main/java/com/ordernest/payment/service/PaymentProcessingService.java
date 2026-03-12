package com.ordernest.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordernest.payment.client.OrderClient;
import com.ordernest.payment.client.OrderDetailsResponse;
import com.ordernest.payment.dto.ProcessPaymentRequest;
import com.ordernest.payment.dto.RazorpayCreateOrderResponse;
import com.ordernest.payment.dto.RazorpayVerifyPaymentRequest;
import com.ordernest.payment.dto.RazorpayVerifyPaymentResponse;
import com.ordernest.payment.event.OrderCancellationEvent;
import com.ordernest.payment.event.OrderCancellationEventType;
import com.ordernest.payment.event.PaymentEvent;
import com.ordernest.payment.event.PaymentEventType;
import com.ordernest.payment.messaging.PaymentEventPublisher;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class PaymentProcessingService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final PaymentEventPublisher paymentEventPublisher;
    private final OrderClient orderClient;
    private final RestClient razorpayClient;
    private final ObjectMapper objectMapper;
    private final String razorpayKeyId;
    private final String razorpayKeySecret;
    private final String razorpayWebhookSecret;

    private final Map<UUID, PendingPaymentAttempt> pendingAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, ConfirmedPaymentReference> confirmedPaymentsByOrderId = new ConcurrentHashMap<>();
    private final Map<String, ConfirmedPaymentReference> confirmedPaymentsByRazorpayPaymentId = new ConcurrentHashMap<>();
    private final Set<UUID> refundInitiatedOrderIds = ConcurrentHashMap.newKeySet();
    private final Set<String> processedRefundIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> refundIdToOrderId = new ConcurrentHashMap<>();

    public PaymentProcessingService(
            PaymentEventPublisher paymentEventPublisher,
            OrderClient orderClient,
            ObjectMapper objectMapper,
            @Value("${app.razorpay.base-url:https://api.razorpay.com}") String razorpayBaseUrl,
            @Value("${app.razorpay.key-id:}") String razorpayKeyId,
            @Value("${app.razorpay.key-secret:}") String razorpayKeySecret,
            @Value("${app.razorpay.webhook-secret:}") String razorpayWebhookSecret
    ) {
        this.paymentEventPublisher = paymentEventPublisher;
        this.orderClient = orderClient;
        this.objectMapper = objectMapper;
        this.razorpayClient = RestClient.builder().baseUrl(razorpayBaseUrl).build();
        this.razorpayKeyId = razorpayKeyId;
        this.razorpayKeySecret = razorpayKeySecret;
        this.razorpayWebhookSecret = razorpayWebhookSecret;
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

    public void processOrderCancellationEvent(OrderCancellationEvent cancellationEvent) {
        if (cancellationEvent == null
                || cancellationEvent.orderId() == null
                || cancellationEvent.eventType() == null
                || cancellationEvent.eventType() != OrderCancellationEventType.CANCALLED) {
            return;
        }

        UUID orderId = parseOrderId(cancellationEvent.orderId());
        ConfirmedPaymentReference reference = confirmedPaymentsByOrderId.get(orderId);
        if (reference == null) {
            log.info("Skipping refund for orderId={} because no confirmed payment reference exists", cancellationEvent.orderId());
            return;
        }

        if (!refundInitiatedOrderIds.add(orderId)) {
            log.info("Skipping duplicate refund initiation for orderId={}", cancellationEvent.orderId());
            return;
        }

        try {
            RazorpayRefundResponse refundResponse = createRazorpayRefund(reference.razorpayPaymentId(), reference.internalOrderId());
            if (refundResponse.id() != null && !refundResponse.id().isBlank()) {
                refundIdToOrderId.put(refundResponse.id(), reference.internalOrderId());
            }
        } catch (Exception ex) {
            refundInitiatedOrderIds.remove(orderId);
            throw ex;
        }
    }

    public void processRefundWebhook(String payload, String signature) {
        validateRazorpayWebhookConfiguration();

        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("Missing X-Razorpay-Signature header");
        }

        String expectedSignature = hmacSha256(payload, razorpayWebhookSecret);
        boolean verified = MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)
        );

        if (!verified) {
            throw new IllegalArgumentException("Invalid webhook signature");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid webhook payload");
        }

        String eventName = extractText(root.at("/event"));
        if (!"refund.processed".equals(eventName)) {
            return;
        }

        String refundStatus = extractText(root.at("/payload/refund/entity/status"));
        if (!"processed".equalsIgnoreCase(refundStatus)) {
            return;
        }

        String refundId = extractText(root.at("/payload/refund/entity/id"));
        if (refundId == null || refundId.isBlank()) {
            log.warn("Skipping refund webhook event because refund id is missing");
            return;
        }

        if (!processedRefundIds.add(refundId)) {
            return;
        }

        String internalOrderId = extractText(root.at("/payload/refund/entity/notes/internal_order_id"));
        if (internalOrderId == null || internalOrderId.isBlank()) {
            internalOrderId = refundIdToOrderId.get(refundId);
        }

        String paymentId = extractText(root.at("/payload/refund/entity/payment_id"));

        ConfirmedPaymentReference reference = null;
        if (internalOrderId != null && !internalOrderId.isBlank()) {
            try {
                reference = confirmedPaymentsByOrderId.get(parseOrderId(internalOrderId));
            } catch (IllegalArgumentException ex) {
                log.warn("Invalid internal_order_id in refund webhook payload: {}", internalOrderId);
            }
        }
        if (reference == null && paymentId != null && !paymentId.isBlank()) {
            reference = confirmedPaymentsByRazorpayPaymentId.get(paymentId);
        }

        if (reference == null) {
            log.warn("Refund webhook processed but payment reference is unknown. refundId={}, orderId={}, paymentId={}",
                    refundId, internalOrderId, paymentId);
            return;
        }

        publishRefundedEvent(reference, paymentId);
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

    private void validateRazorpayWebhookConfiguration() {
        if (razorpayWebhookSecret == null || razorpayWebhookSecret.isBlank()) {
            throw new IllegalStateException("Razorpay webhook secret is not configured");
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

    private RazorpayRefundResponse createRazorpayRefund(String razorpayPaymentId, String internalOrderId) {
        validateRazorpayConfiguration();

        String basicAuth = Base64.getEncoder()
                .encodeToString((razorpayKeyId + ":" + razorpayKeySecret).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = Map.of(
                "notes", Map.of("internal_order_id", internalOrderId)
        );

        RazorpayRefundResponse response = razorpayClient.post()
                .uri("/v1/payments/{paymentId}/refund", razorpayPaymentId)
                .header("Authorization", "Basic " + basicAuth)
                .body(body)
                .retrieve()
                .body(RazorpayRefundResponse.class);

        if (response == null || response.id() == null || response.id().isBlank()) {
            throw new IllegalStateException("Unable to create Razorpay refund");
        }

        log.info("Refund initiated for orderId={}, paymentId={}, refundId={}, status={}",
                internalOrderId, razorpayPaymentId, response.id(), response.status());

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

    private void publishSuccessEvent(PendingPaymentAttempt attempt, String razorpayPaymentId) {
        paymentEventPublisher.publish(new PaymentEvent(
                attempt.productId(),
                attempt.quantity(),
                attempt.amount(),
                attempt.currency(),
                PaymentEventType.PAYMENT_SUCCESS,
                attempt.internalOrderId(),
                razorpayPaymentId,
                null,
                Instant.now()
        ));

        UUID orderId = parseOrderId(attempt.internalOrderId());
        ConfirmedPaymentReference reference = new ConfirmedPaymentReference(
                attempt.internalOrderId(),
                attempt.productId(),
                attempt.quantity(),
                attempt.amount(),
                attempt.currency(),
                razorpayPaymentId
        );

        confirmedPaymentsByOrderId.put(orderId, reference);
        confirmedPaymentsByRazorpayPaymentId.put(razorpayPaymentId, reference);
    }

    private void publishFailureEvent(PendingPaymentAttempt attempt, String razorpayPaymentId, String reason) {
        paymentEventPublisher.publish(new PaymentEvent(
                attempt.productId(),
                attempt.quantity(),
                attempt.amount(),
                attempt.currency(),
                PaymentEventType.PAYMENT_FAILED,
                attempt.internalOrderId(),
                razorpayPaymentId,
                reason,
                Instant.now()
        ));
    }

    private void publishRefundedEvent(ConfirmedPaymentReference reference, String razorpayPaymentId) {
        paymentEventPublisher.publish(new PaymentEvent(
                reference.productId(),
                reference.quantity(),
                reference.amount(),
                reference.currency(),
                PaymentEventType.PAYMENT_REFUNDED,
                reference.internalOrderId(),
                razorpayPaymentId == null || razorpayPaymentId.isBlank() ? reference.razorpayPaymentId() : razorpayPaymentId,
                "Refund confirmed by Razorpay webhook",
                Instant.now()
        ));
    }

    private String extractText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
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

    private record RazorpayRefundResponse(
            String id,
            String status,
            String payment_id
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

    private record ConfirmedPaymentReference(
            String internalOrderId,
            UUID productId,
            Integer quantity,
            BigDecimal amount,
            String currency,
            String razorpayPaymentId
    ) {
    }
}
