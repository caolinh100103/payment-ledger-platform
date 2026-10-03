package com.payledger.common.openapi;

import com.payledger.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The web app is typed against {@code frontend/openapi/core.json}, a committed copy of this service's OpenAPI document
 * (ADR 0016). This test fails when the controllers and that copy disagree, so an API change and the frontend types it
 * affects land in the same commit. To accept a change, regenerate the copy:
 *
 * <pre>./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true</pre>
 *
 * <p>then {@code npm run generate:api} in {@code frontend}, and fix whatever no longer compiles there.
 */
class OpenApiContractTest extends ApiTestSupport {

    static final Path COMMITTED = Path.of("..", "frontend", "openapi", "core.json");

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Test
    void theCommittedDocumentMatchesTheApi() throws IOException {
        String served = bodyOf(mvc.get().uri("/v3/api-docs").exchange());
        JsonNode actual = JSON.readTree(served);

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, JSON.writeValueAsString(actual) + "\n", StandardCharsets.UTF_8);
        }

        assertThat(COMMITTED).as("%s; create it with -Dopenapi.update=true", COMMITTED).exists();
        assertThat(JSON.readTree(Files.readString(COMMITTED, StandardCharsets.UTF_8)))
                .as("The API changed: regenerate %s with -Dopenapi.update=true, then run npm run generate:api in "
                        + "frontend", COMMITTED)
                .isEqualTo(actual);
    }
}
