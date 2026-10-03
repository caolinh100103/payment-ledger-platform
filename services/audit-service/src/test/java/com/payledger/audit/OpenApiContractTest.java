package com.payledger.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails when this service's API and {@code frontend/openapi/audit.json}, the copy the web app is typed against,
 * disagree (ADR 0016). To accept a change: {@code ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.update=true}, then
 * {@code npm run generate:api} in {@code frontend}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiContractTest {

    static final Path COMMITTED = Path.of("..", "..", "frontend", "openapi", "audit.json");

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    @Autowired
    MockMvcTester mvc;

    @Test
    void theCommittedDocumentMatchesTheApi() throws IOException {
        String served = mvc.get().uri("/v3/api-docs").exchange().getMvcResult().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
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
