package com.payledger.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiIntegrationTest extends ApiTestSupport {

    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void signsUpAndSignsInWithAnEs256AccessToken() throws Exception {
        String username = uniqueUsername();
        MvcTestResult signup = signUp(username, PASSWORD);
        assertThat(signup).hasStatus(HttpStatus.CREATED);
        assertThat(signup).bodyJson().extractingPath("$.role").isEqualTo("CUSTOMER");
        String userId = jsonPath(signup, "$.id");

        MvcTestResult login = login(username, PASSWORD);

        assertThat(login).hasStatusOk();
        assertThat(login).bodyJson().extractingPath("$.tokenType").isEqualTo("Bearer");
        assertThat(login).bodyJson().extractingPath("$.expiresIn").isEqualTo(300);
        assertThat(login).bodyJson().extractingPath("$.refreshToken").isNotNull();
        assertThat(login).bodyJson().extractingPath("$.refreshExpiresIn").isEqualTo(900);
        SignedJWT token = SignedJWT.parse(jsonPath(login, "$.accessToken"));
        assertThat(token.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(token.getHeader().getType()).isEqualTo(new JOSEObjectType("at+jwt"));
        assertThat(token.getHeader().getKeyID()).isEqualTo(jsonPath(mvc.get().uri("/.well-known/jwks.json").exchange(),
                "$.keys[0].kid"));
        JWTClaimsSet claims = token.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(userId);
        assertThat(claims.getIssuer()).isEqualTo("http://localhost:8080");
        assertThat(claims.getAudience()).containsExactly("payledger-api");
        assertThat(claims.getStringListClaim("roles")).containsExactly("CUSTOMER");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("payledger-app");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getStringClaim("sid")).isNotBlank();
        assertThat(claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()).isEqualTo(300_000);

        assertThat(mvc.get().uri("/api/v1/users/me").header("Authorization", "Bearer " + token.serialize()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.username").isEqualTo(username);
    }

    @Test
    void storesOnlyAnArgon2idHashOfThePassword() {
        String username = uniqueUsername();
        signUp(username, PASSWORD);

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, username);

        assertThat(hash).startsWith("{argon2}$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain(PASSWORD);
    }

    @Test
    void usernamesAreCaseInsensitive() {
        String username = uniqueUsername();
        signUp(username.toUpperCase(), PASSWORD);

        assertThat(login(username, PASSWORD)).hasStatusOk();
        assertThat(signUp(username, PASSWORD))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("USERNAME_TAKEN");
    }

    @Test
    void rejectsPasswordsAgainstTheNistPolicy() {
        String username = uniqueUsername();

        assertThat(signUp(username, "Sh0rt&Complex!")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThat(signUp(username, "my name is " + username)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThat(signUp(username, "i love payledger so much")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.code").isEqualTo("PASSWORD_POLICY_VIOLATION");
        // 15 characters of a Vietnamese passphrase, counted as letters rather than UTF-16 units.
        assertThat(signUp(username, "mưa rơi lất phất")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void wrongPasswordAndUnknownUsernameGetTheSameAnswer() {
        String username = uniqueUsername();
        signUp(username, PASSWORD);

        MvcTestResult wrongPassword = login(username, "not the right password");
        MvcTestResult unknownUser = login(uniqueUsername(), PASSWORD);

        assertThat(wrongPassword).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        assertThat(bodyOf(unknownUser)).isEqualTo(bodyOf(wrongPassword));
    }

    @Test
    void fifthWrongPasswordLocksTheUserOutEvenForTheRightPassword() {
        String username = uniqueUsername();
        signUp(username, PASSWORD);

        for (int i = 1; i <= 4; i++) {
            assertThat(login(username, "wrong password " + i))
                    .bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
        }
        MvcTestResult fifth = login(username, "wrong password 5");
        MvcTestResult rightPassword = login(username, PASSWORD);

        assertThat(fifth).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(fifth).bodyJson().extractingPath("$.code").isEqualTo("ACCOUNT_LOCKED");
        assertThat(Long.parseLong(fifth.getMvcResult().getResponse().getHeader("Retry-After"))).isBetween(890L, 900L);
        assertThat(rightPassword).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(rightPassword).bodyJson().extractingPath("$.code").isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void aSuccessfulSignInResetsTheCount() {
        String username = uniqueUsername();
        signUp(username, PASSWORD);

        for (int i = 0; i < 4; i++) {
            login(username, "wrong password");
        }
        assertThat(login(username, PASSWORD)).hasStatusOk();
        for (int i = 0; i < 4; i++) {
            login(username, "wrong password");
        }

        assertThat(login(username, PASSWORD)).hasStatusOk();
    }

    @Test
    void operatorUnlocksALockedOutUser() {
        String username = uniqueUsername();
        String userId = jsonPath(signUp(username, PASSWORD), "$.id");
        for (int i = 0; i < 5; i++) {
            login(username, "wrong password");
        }

        assertThat(mvc.get().uri("/api/v1/users/{id}", userId))
                .bodyJson().extractingPath("$.lockedUntil").isNotNull();
        assertThat(mvc.post().uri("/api/v1/users/{id}/unlock", userId))
                .hasStatusOk()
                .bodyJson().extractingPath("$.lockedUntil").isNull();
        assertThat(login(username, PASSWORD)).hasStatusOk();
    }

    /**
     * 30 guesses at once. The user row is locked while each password is checked, so the attempts are counted one
     * after the other: 4 are rejected, the 5th locks the user out, and the other 25 are refused without checking
     * the password at all. Without the row lock, all 30 would read "0 failed attempts" and all would be checked.
     */
    @Test
    void parallelGuessesCannotGetPastTheLockout() throws Exception {
        String username = uniqueUsername();
        signUp(username, PASSWORD);
        int guesses = 30;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> codes = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(guesses)) {
            for (int i = 0; i < guesses; i++) {
                String guess = "guess number " + i + " is wrong";
                codes.add(pool.submit(() -> {
                    start.await();
                    return jsonPath(login(username, guess), "$.code");
                }));
            }
            start.countDown();
        }

        Map<String, Long> counts = new HashMap<>();
        for (Future<String> code : codes) {
            counts.merge(code.get(), 1L, Long::sum);
        }
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("INVALID_CREDENTIALS", 4L, "ACCOUNT_LOCKED", 26L));
        assertThat(jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE username = ?", Integer.class,
                username)).isEqualTo(5);
    }

    @Test
    void requestWithoutTokenIsRejectedWithAProblem() {
        MvcTestResult result = mvc.get().uri("/api/v1/accounts/{id}", UUID.randomUUID())
                .header("Authorization", "")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(result.getMvcResult().getResponse().getHeader("WWW-Authenticate"))
                .startsWith("Bearer").contains("resource_metadata=\"http://localhost/.well-known/oauth-protected-resource\"");
    }

    @Test
    void describesItselfAsAnOAuthProtectedResource() {
        MvcTestResult metadata = mvc.get().uri("/.well-known/oauth-protected-resource").header("Authorization", "")
                .exchange();

        assertThat(metadata).hasStatusOk();
        assertThat(metadata).bodyJson().extractingPath("$.authorization_servers").asArray()
                .containsExactly("http://localhost:8080");
        assertThat(metadata).bodyJson().extractingPath("$.bearer_methods_supported").asArray().containsExactly("header");
    }

    @Test
    void rejectsTokensThatAreExpiredForgedOrMeantForSomethingElse() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String valid = sign(claims -> claims);

        Map<String, String> invalid = Map.of(
                "expired", sign(claims -> claims.issuedAt(now.minusSeconds(900)).expiresAt(now.minusSeconds(600))),
                "other audience", sign(claims -> claims.audience(List.of("some-other-api"))),
                "other issuer", sign(claims -> claims.issuer("https://evil.example")),
                "ID token, not an access token", signWithType("JWT"),
                "tampered payload", tamper(valid),
                "signed with another key", signWithForeignKey(),
                "alg none", unsigned(valid));

        assertThat(withToken(valid)).hasStatusOk();
        Map<String, Integer> statuses = invalid.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> withToken(entry.getValue()).getMvcResult().getResponse().getStatus()));
        assertThat(statuses).allSatisfy((name, status) -> assertThat(status).as(name).isEqualTo(401));
        MvcTestResult expired = withToken(invalid.get("expired"));
        assertThat(expired).bodyJson().extractingPath("$.code").isEqualTo("INVALID_TOKEN");
        assertThat(expired.getMvcResult().getResponse().getHeader("WWW-Authenticate")).contains("error=\"invalid_token\"");
    }

    @Test
    void publishesOnlyThePublicKey() {
        MvcTestResult jwks = mvc.get().uri("/.well-known/jwks.json").header("Authorization", "").exchange();

        assertThat(jwks).hasStatusOk();
        assertThat(jwks).bodyJson().extractingPath("$.keys.length()").isEqualTo(1);
        assertThat(jwks).bodyJson().extractingPath("$.keys[0].kty").isEqualTo("EC");
        assertThat(jwks).bodyJson().extractingPath("$.keys[0].crv").isEqualTo("P-256");
        assertThat(bodyOf(jwks)).doesNotContain("\"d\"");
    }

    @Test
    void onlyAnAdminCreatesUsers() {
        String customerToken = loginToken(signedUpCustomer());
        String adminToken = accessTokenFor(UUID.randomUUID(), Role.ADMIN);
        String body = """
                {"username": "%s", "password": "%s", "role": "OPERATOR"}
                """.formatted(uniqueUsername(), PASSWORD);

        assertThat(mvc.post().uri("/api/v1/users").header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("ACCESS_DENIED");
        assertThat(mvc.post().uri("/api/v1/users").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.role").isEqualTo("OPERATOR");
    }

    private MvcTestResult withToken(String token) {
        return mvc.get().uri("/api/v1/users/me").header("Authorization", "Bearer " + token).exchange();
    }

    private String signedUpCustomer() {
        String username = uniqueUsername();
        signUp(username, PASSWORD);
        return username;
    }

    private String loginToken(String username) {
        return jsonPath(login(username, PASSWORD), "$.accessToken");
    }

    private String accessTokenFor(UUID userId, Role role) {
        return sign(claims -> claims.subject(userId.toString()).claim("roles", List.of(role.name())));
    }

    /** A token signed with the real key; {@code customize} changes the claims of an otherwise valid one. */
    private String sign(Function<JwtClaimsSet.Builder, JwtClaimsSet.Builder> customize) {
        return encode("at+jwt", customize);
    }

    private String signWithType(String type) {
        return encode(type, claims -> claims);
    }

    private String encode(String type, Function<JwtClaimsSet.Builder, JwtClaimsSet.Builder> customize) {
        String userId = jsonPath(signUp(uniqueUsername(), PASSWORD), "$.id");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("http://localhost:8080")
                .subject(userId)
                .audience(List.of("payledger-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString())
                .claim("client_id", "payledger-app")
                .claim("roles", List.of("CUSTOMER"));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).type(type)
                .keyId(jsonPath(mvc.get().uri("/.well-known/jwks.json").exchange(), "$.keys[0].kid")).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, customize.apply(claims).build())).getTokenValue();
    }

    /** An attacker's own P-256 key, claiming the real key id. */
    private String signWithForeignKey() throws Exception {
        ECKey attackerKey = new ECKeyGenerator(Curve.P_256).generate();
        String kid = jsonPath(mvc.get().uri("/.well-known/jwks.json").exchange(), "$.keys[0].kid");
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType("at+jwt"))
                .keyID(kid).build(), SignedJWT.parse(sign(claims -> claims)).getJWTClaimsSet());
        jwt.sign(new ECDSASigner(attackerKey));
        return jwt.serialize();
    }

    /** Grants the token ADMIN in the payload, keeping the original signature. */
    private static String tamper(String token) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1])).replace("\"CUSTOMER\"", "\"ADMIN\"");
        return parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes()) + "." + parts[2];
    }

    /** The classic {@code alg: none} attack: same claims, no signature. */
    private static String unsigned(String token) {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"at+jwt\"}".getBytes());
        return header + "." + token.split("\\.")[1] + ".";
    }
}
