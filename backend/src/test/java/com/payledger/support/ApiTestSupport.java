package com.payledger.support;

import com.jayway.jsonpath.JsonPath;
import com.payledger.TestcontainersConfiguration;
import com.payledger.security.Role;
import com.payledger.security.token.AccessTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Shared helpers for API integration tests. All tests share one PostgreSQL container and Spring context. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DefaultAccessToken.class})
public abstract class ApiTestSupport {

    protected static final String PASSWORD = "correct horse battery staple";

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AccessTokens accessTokens;

    /** An Authorization header value for a new user with {@code role}. */
    protected String bearer(Role role) {
        return "Bearer " + accessTokens.issue(UUID.randomUUID(), role, UUID.randomUUID()).value();
    }

    protected String openAccount(String currency) {
        return openAccount("owner-" + UUID.randomUUID(), currency);
    }

    protected String openAccount(String ownerId, String currency) {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ownerId": "%s", "currency": "%s"}
                        """.formatted(ownerId, currency))
                .exchange();
        return jsonPath(result, "$.id");
    }

    protected MvcTestResult deposit(String accountId, long amount, String currency) {
        return postWithKey("/api/v1/deposits", UUID.randomUUID().toString(), """
                {"accountId": "%s", "amount": %d, "currency": "%s", "description": "Top-up"}
                """.formatted(accountId, amount, currency));
    }

    protected MvcTestResult transfer(String from, String to, long amount, String currency) {
        return postWithKey("/api/v1/transfers", UUID.randomUUID().toString(), transferBody(from, to, amount, currency));
    }

    protected static String transferBody(String from, String to, long amount, String currency) {
        return """
                {"sourceAccountId": "%s", "destinationAccountId": "%s", "amount": %d,
                 "currency": "%s", "description": "Tiền nhà tháng 10"}
                """.formatted(from, to, amount, currency);
    }

    protected MvcTestResult postWithKey(String uri, String idempotencyKey, String json) {
        return mvc.post().uri(uri)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    protected MvcTestResult signUp(String username, String password) {
        return mvc.post().uri("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "%s", "password": "%s"}
                        """.formatted(username, password))
                .exchange();
    }

    protected MvcTestResult login(String username, String password) {
        return mvc.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "%s", "password": "%s"}
                        """.formatted(username, password))
                .exchange();
    }

    protected static String uniqueUsername() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String bodyOf(MvcTestResult result) {
        return new String(result.getMvcResult().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    protected String fundedAccount(String currency, long amount) {
        String id = openAccount(currency);
        deposit(id, amount, currency);
        return id;
    }

    protected long balanceOf(String accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?::uuid", Long.class, accountId);
    }

    protected String systemAccountId(String currency) {
        return jdbc.queryForObject("SELECT id::text FROM accounts WHERE type = 'SYSTEM' AND currency = ?",
                String.class, currency);
    }

    protected long ledgerEntryCount(String transferId) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?::uuid",
                Long.class, transferId);
    }

    protected static <T> T jsonPath(MvcTestResult result, String path) {
        return JsonPath.read(bodyOf(result), path);
    }
}
