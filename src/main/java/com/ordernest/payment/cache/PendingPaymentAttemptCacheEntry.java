package com.ordernest.payment.cache;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

@RedisHash("payment:pending-attempt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PendingPaymentAttemptCacheEntry implements Serializable {

    @Id
    private String orderId;

    private String razorpayOrderId;
    private String productId;
    private Integer quantity;
    private BigDecimal amount;
    private String currency;
    private Instant createdAt;

    @TimeToLive
    private Long ttlSeconds;
}
