package com.hookflow.audit.application;

import com.hookflow.audit.infrastructure.AuditRetentionRepository;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Profile("!skeleton")
@Service
public class AuditRetentionService {
    private final AuditRetentionRepository repository;

    public AuditRetentionService(AuditRetentionRepository repository) { this.repository = repository; }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public int deleteExpired(LocalDateTime cutoff) { return repository.deleteExpired(cutoff); }
}
