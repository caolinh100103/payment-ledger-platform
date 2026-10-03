package com.payledger.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tokens issued by the core, verified here with its public keys: only auditors read the trail. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditSecurityIntegrationTest {

    private static final String TRAIL = "/api/v1/audit-events/verification";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestJwtIssuer tokens;

    @Test
    void auditorsAndAdminsReadTheTrail() {
        assertThat(get(tokens.bearer("auditor-1", "AUDITOR"))).hasStatusOk();
        assertThat(get(tokens.bearer("admin-1", "ADMIN"))).hasStatusOk();
    }

    @Test
    void nobodyElseDoes() {
        Map<String, String> roles = Map.of("customer", "CUSTOMER", "operator", "OPERATOR");

        roles.forEach((name, role) -> {
            MvcTestResult result = get(tokens.bearer(name, role));
            assertThat(result).as(role).hasStatus(HttpStatus.FORBIDDEN);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("ACCESS_DENIED");
        });
    }

    @Test
    void requestsWithoutAValidTokenAreRejected() throws Exception {
        assertThat(mvc.get().uri(TRAIL))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(get("Bearer " + tokens.forgedToken("auditor-1", "AUDITOR")))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_TOKEN");
        assertThat(get("Bearer " + tokens.token("auditor-1", "AUDITOR", claims -> claims.audience("another-api"))))
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(get("Bearer " + tokens.token("auditor-1", "AUDITOR",
                claims -> claims.issuer("https://evil.example"))))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthStaysOpenForProbes() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }

    private MvcTestResult get(String authorization) {
        return mvc.get().uri(TRAIL).header("Authorization", authorization).exchange();
    }
}
