package com.payledger.security.user;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class UserLookupApiIntegrationTest extends ApiTestSupport {

    @Test
    void anOperatorFindsACustomerByExactUsernameWhateverTheCase() {
        String username = uniqueUsername();
        String id = jsonPath(signUp(username, PASSWORD), "$.id");

        assertThat(mvc.get().uri("/api/v1/users").param("username", username.toUpperCase()).with(asOperator()))
                .hasStatusOk()
                .bodyJson().extractingPath("$[0].id").isEqualTo(id);
        // No partial matches: the user list cannot be walked letter by letter.
        assertThat(mvc.get().uri("/api/v1/users").param("username", username.substring(0, 6)).with(asOperator()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.length()").isEqualTo(0);
    }

    @Test
    void customersAndAuditorsCannotLookUpUsers() {
        assertThat(mvc.get().uri("/api/v1/users").param("username", "admin").with(asCustomer(newCustomer())))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(mvc.get().uri("/api/v1/users").param("username", "admin").with(asAuditor()))
                .hasStatus(HttpStatus.FORBIDDEN);
    }
}
