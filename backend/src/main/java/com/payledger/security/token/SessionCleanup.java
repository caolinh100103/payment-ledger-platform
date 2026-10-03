package com.payledger.security.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes sessions past their absolute lifetime. Running it on several instances is harmless. */
@Component
class SessionCleanup {

    private static final Logger log = LoggerFactory.getLogger(SessionCleanup.class);
    private static final int BATCH_SIZE = 1_000;

    private final Sessions sessions;

    SessionCleanup(Sessions sessions) {
        this.sessions = sessions;
    }

    @Scheduled(initialDelayString = "${payledger.security.refresh.cleanup-interval:PT1H}",
            fixedDelayString = "${payledger.security.refresh.cleanup-interval:PT1H}")
    void deleteExpiredSessions() {
        int total = 0;
        int deleted;
        do {
            deleted = sessions.deleteExpired(BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);
        if (total > 0) {
            log.info("Deleted {} expired sign-in sessions", total);
        }
    }
}
