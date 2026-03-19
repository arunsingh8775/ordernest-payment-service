package com.ordernest.payment.repository;

import com.ordernest.payment.cache.PendingPaymentAttemptCacheEntry;
import org.springframework.data.repository.CrudRepository;

public interface PendingPaymentAttemptRepository extends CrudRepository<PendingPaymentAttemptCacheEntry, String> {
}
