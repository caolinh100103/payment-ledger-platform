package com.payledger.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/** A customer's balance-change messages are theirs alone; operators may look them up. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationSecurityIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestJwtIssuer tokens;

    @Test
    void aCustomerReadsTheirOwnMessages() {
        assertThat(mvc.get().uri("/api/v1/notifications").header("Authorization", tokens.bearer("alice", "CUSTOMER")))
                .hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/notifications?recipientId=alice")
                .header("Authorization", tokens.bearer("alice", "CUSTOMER")))
                .hasStatusOk();
    }

    @Test
    void aCustomerCannotReadSomeoneElsesMessages() {
        assertThat(mvc.get().uri("/api/v1/notifications?recipientId=bob")
                .header("Authorization", tokens.bearer("alice", "CUSTOMER")))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("ACCESS_DENIED");
    }

    @Test
    void anOperatorReadsAnyCustomersMessages() {
        assertThat(mvc.get().uri("/api/v1/notifications?recipientId=bob")
                .header("Authorization", tokens.bearer("operator-1", "OPERATOR")))
                .hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/notifications?recipientId=bob")
                .header("Authorization", tokens.bearer("admin-1", "ADMIN")))
                .hasStatusOk();
    }

    @Test
    void anAuditorHasNoBusinessHere() {
        assertThat(mvc.get().uri("/api/v1/notifications?recipientId=bob")
                .header("Authorization", tokens.bearer("auditor-1", "AUDITOR")))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void requestsWithoutAValidTokenAreRejected() throws Exception {
        assertThat(mvc.get().uri("/api/v1/notifications?recipientId=bob")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/notifications")
                .header("Authorization", "Bearer " + tokens.forgedToken("bob", "CUSTOMER")))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.code").isEqualTo("INVALID_TOKEN");
    }
}
