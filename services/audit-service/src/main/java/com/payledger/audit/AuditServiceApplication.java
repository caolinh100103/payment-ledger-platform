package com.payledger.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Consumes every PayLedger domain event and records it in a tamper-evident, append-only log. It has its own
 * database: the core service cannot write to the audit trail, and the audit trail cannot be lost with it.
 */
@SpringBootApplication
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
