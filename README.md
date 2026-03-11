# OrderNest Payment Service

Spring Boot payment service for OrderNest with Razorpay test-mode integration.

## Endpoints

### 1) Create Razorpay order
- `POST /api/payments/create-order`

Request:
```json
{
  "orderId": "74f75b15-9d9f-4a68-a0d8-8f1f0dacc939"
}
```

Response:
```json
{
  "internalOrderId": "74f75b15-9d9f-4a68-a0d8-8f1f0dacc939",
  "razorpayOrderId": "order_Q8w1f6PZsP5l8v",
  "razorpayKeyId": "rzp_test_xxxxx",
  "amount": 49900,
  "currency": "INR"
}
```

Behavior:
- Fetches order details from order service.
- Creates a Razorpay order on `/v1/orders`.
- Stores a short-lived pending payment attempt in memory for later verification.

### 2) Verify Razorpay payment signature
- `POST /api/payments/verify`

Request:
```json
{
  "orderId": "74f75b15-9d9f-4a68-a0d8-8f1f0dacc939",
  "razorpayOrderId": "order_Q8w1f6PZsP5l8v",
  "razorpayPaymentId": "pay_Q8w2K9sw7cx9wQ",
  "razorpaySignature": "7ee4..."
}
```

Behavior:
- Validates that the submitted Razorpay order matches pending server state.
- Verifies signature using HMAC-SHA256 (`order_id|payment_id`) with Razorpay secret.
- Publishes Kafka event:
  - `PAYMENT_SUCCESS` if signature is valid
  - `PAYMENT_FAILED` if signature is invalid/mismatched

## Kafka Event
Published event payload (`PaymentEvent`):
- `eventType` (`PAYMENT_SUCCESS` / `PAYMENT_FAILED`)
- `orderId`
- `paymentId`
- `amount`
- `currency`
- `timestamp`
- `reason` (only for failures)

## Environment Variables
- `JWT_SECRET` (must match auth/order services)
- `RAZORPAY_KEY_ID` (test key id, `rzp_test_*`)
- `RAZORPAY_KEY_SECRET` (test secret)
