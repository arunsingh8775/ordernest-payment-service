package com.ordernest.payment.repository;

import com.ordernest.payment.entity.PaymentRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {

    Optional<PaymentRecord> findByOrderId(UUID orderId);

    Optional<PaymentRecord> findByRazorpayPaymentId(String razorpayPaymentId);

    Optional<PaymentRecord> findByRefundId(String refundId);

    boolean existsByRefundId(String refundId);
}
