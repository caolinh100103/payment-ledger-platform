package com.payledger.support;

import com.jayway.jsonpath.JsonPath;
import com.payledger.TestcontainersConfiguration;
import com.payledger.security.Actor;
import com.payledger.security.Role;
import com.payledger.security.apikey.ApiKeyAuthenticationFilter;
import com.payledger.security.apikey.ApiKeyScope;
import com.payledger.security.apikey.ApiKeys;
import com.payledger.security.token.AccessTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Shared helpers for API integration tests. All tests share one PostgreSQL container and Spring context.
 *
 * <p>Requests go through the real security filters. The {@code as...} helpers sign a request in with a real access
 * token (or the bank's API key); a request without one is anonymous.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class ApiTestSupport {

    protected static final String PASSWORD = "correct horse battery staple";

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AccessTokens accessTokens;

    @Autowired
    protected ApiKeys apiKeys;

    private String bankKey;

    // ---------------------------------------------------------------------------------------------------------------
    // Who is calling
    // ---------------------------------------------------------------------------------------------------------------

    /** A new customer: just a user id, since access tokens are verified without looking the user up. */
    protected static String newCustomer() {
        return UUID.randomUUID().toString();
    }

    protected RequestPostProcessor asUser(String userId, Role role) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, bearer(userId, role));
            return request;
        };
    }

    protected RequestPostProcessor asCustomer(String userId) {
        return asUser(userId, Role.CUSTOMER);
    }

    /** The customer who owns {@code accountId}. */
    protected RequestPostProcessor asOwnerOf(String accountId) {
        return asCustomer(ownerOf(accountId));
    }

    protected RequestPostProcessor asOperator() {
        return asUser(newCustomer(), Role.OPERATOR);
    }

    protected RequestPostProcessor asAuditor() {
        return asUser(newCustomer(), Role.AUDITOR);
    }

    protected RequestPostProcessor asAdmin() {
        return asUser(newCustomer(), Role.ADMIN);
    }

    /** The partner bank integration: an API key with the {@code deposits:write} scope. */
    protected RequestPostProcessor asBank() {
        if (bankKey == null) {
            bankKey = apiKeys.create("Partner bank (test)", Set.of(ApiKeyScope.DEPOSITS_WRITE), null, Actor.SYSTEM)
                    .key();
        }
        String key = bankKey;
        return request -> {
            request.addHeader(ApiKeyAuthenticationFilter.HEADER, key);
            return request;
        };
    }

    /** An Authorization header value for a new user with {@code role}. */
    protected String bearer(Role role) {
        return bearer(newCustomer(), role);
    }

    protected String bearer(String userId, Role role) {
        return "Bearer " + accessTokens.issue(UUID.fromString(userId), role, UUID.randomUUID()).value();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Accounts and money
    // ---------------------------------------------------------------------------------------------------------------

    /** Opens an account for a new customer. */
    protected String openAccount(String currency) {
        return openAccount(newCustomer(), currency);
    }

    protected String openAccount(String ownerId, String currency) {
        MvcTestResult result = mvc.post().uri("/api/v1/accounts")
                .with(asCustomer(ownerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currency": "%s"}
                        """.formatted(currency))
                .exchange();
        return jsonPath(result, "$.id");
    }

    protected MvcTestResult deposit(String accountId, long amount, String currency) {
        return postWithKey("/api/v1/deposits", UUID.randomUUID().toString(), """
                {"accountId": "%s", "amount": %d, "currency": "%s", "description": "Top-up"}
                """.formatted(accountId, amount, currency), asBank());
    }

    /** A transfer made by the owner of {@code from}. */
    protected MvcTestResult transfer(String from, String to, long amount, String currency) {
        return postWithKey("/api/v1/transfers", UUID.randomUUID().toString(), transferBody(from, to, amount, currency),
                asOwnerOf(from));
    }

    protected static String transferBody(String from, String to, long amount, String currency) {
        return """
                {"sourceAccountId": "%s", "destinationAccountId": "%s", "amount": %d,
                 "currency": "%s", "description": "Tiền nhà tháng 10"}
                """.formatted(from, to, amount, currency);
    }

    protected MvcTestResult postWithKey(String uri, String idempotencyKey, String json, RequestPostProcessor caller) {
        return mvc.post().uri(uri)
                .with(caller)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    protected String fundedAccount(String currency, long amount) {
        String id = openAccount(currency);
        deposit(id, amount, currency);
        return id;
    }

    protected String ownerOf(String accountId) {
        return jdbc.queryForObject("SELECT owner_id FROM accounts WHERE id = ?::uuid", String.class, accountId);
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

    // ---------------------------------------------------------------------------------------------------------------
    // Users
    // ---------------------------------------------------------------------------------------------------------------

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

    // ---------------------------------------------------------------------------------------------------------------
    // Responses
    // ---------------------------------------------------------------------------------------------------------------

    protected static String bodyOf(MvcTestResult result) {
        return new String(result.getMvcResult().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    protected static <T> T jsonPath(MvcTestResult result, String path) {
        return JsonPath.read(bodyOf(result), path);
    }
}
