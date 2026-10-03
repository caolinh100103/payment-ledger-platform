package com.payledger.security.apikey;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFormatTest {

    @RepeatedTest(20)
    void generatedKeysAreWellFormed() {
        String key = ApiKeyFormat.generate();

        assertThat(key).matches("^plk_[0-9A-Za-z]{38}$");
        assertThat(ApiKeyFormat.isWellFormed(key)).isTrue();
    }

    @Test
    void anyMistypedCharacterBreaksTheChecksum() {
        String key = ApiKeyFormat.generate();

        for (int i = ApiKeyFormat.PREFIX.length(); i < key.length(); i++) {
            char replacement = key.charAt(i) == 'a' ? 'b' : 'a';
            String typo = key.substring(0, i) + replacement + key.substring(i + 1);
            assertThat(ApiKeyFormat.isWellFormed(typo)).as("typo at %d", i).isFalse();
        }
    }

    @Test
    void rejectsAnythingElse() {
        assertThat(ApiKeyFormat.isWellFormed(null)).isFalse();
        assertThat(ApiKeyFormat.isWellFormed("")).isFalse();
        assertThat(ApiKeyFormat.isWellFormed("sk_live_" + "a".repeat(38))).isFalse();
        assertThat(ApiKeyFormat.isWellFormed(ApiKeyFormat.generate() + "x")).isFalse();
    }

    @Test
    void theDisplayPrefixIsTooShortToUse() {
        String key = ApiKeyFormat.generate();

        assertThat(ApiKeyFormat.displayPrefix(key)).hasSize(12).isEqualTo(key.substring(0, 12));
    }
}
