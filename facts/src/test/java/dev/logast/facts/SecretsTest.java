package dev.logast.facts;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** #183: facts never carry secrets; test stubs and local defaults are kept. */
class SecretsTest {
    @ParameterizedTest(name = "{0} = {1} -> {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            # by name
            DB_PASSWORD                  | Sup3r-S3cret!            | redacted
            spring.datasource.password   | hunter2-prod             | redacted
            PAYMENT_API_KEY              | 9f8e7d6c5b4a             | redacted
            CLIENT_SECRET                | abcdEFGH1234             | redacted
            AZURE_STORAGE_CONNECTION_STRING | UseDevelopmentStorage  | redacted
            auth.token                   | opaque-token-value       | redacted
            # by shape, whatever the name
            BEARER                       | eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.abcdefghijklmnop | redacted
            CERT                         | -----BEGIN PRIVATE KEY-----MIIE | redacted
            ORDERS_DB_URL                | postgres://orders:Xk29fjQ@db:5432/orders | redacted
            STORAGE                      | DefaultEndpointsProtocol=https;AccountName=st;AccountKey=a1b2c3d4e5f6== | redacted
            SOME_VALUE                   | AKIAIOSFODNN7EXAMPLE      | redacted
            GH                           | ghp_abcdefghijklmnopqrstuvwxyz0123456789 | redacted
            RANDOM                       | q8Zr2LmX9vB4kT7wN1pY6sH3dF5gJ0cA | redacted
            # kept: no secret
            SHIPPING_URL                 | http://shipping:8080      | kept
            TOKEN_URL                    | https://login.example.com/oauth2/token | kept
            PASSWORD_MIN_LENGTH          | 12                        | kept
            JWT_EXPIRY_SECONDS           | 3600                      | kept
            AUTH_ENABLED                 | true                      | kept
            # kept: test stubs, placeholders, references, local defaults
            DB_PASSWORD                  | test                      | kept
            DB_PASSWORD                  | changeme                  | kept
            DB_PASSWORD                  | ${DB_PASSWORD}            | kept
            DB_PASSWORD                  | secret:kv-orders/db-password | kept
            API_KEY                      | dummy-key                 | kept
            SPRING_DATASOURCE_URL        | jdbc:postgresql://localhost:5432/orders | kept
            ORDERS_DB_URL                | postgres://orders:test@localhost:5432/orders | kept
            SPRING_PROFILES_ACTIVE       | prod                      | kept
            """)
    void redactsWhatLooksSecret(String name, String value, String expected) {
        String cleaned = Secrets.clean(name, value);
        if (expected.equals("redacted")) {
            assertThat(cleaned).as(name).isEqualTo(Secrets.REDACTED);
        } else {
            assertThat(cleaned).as(name).isEqualTo(value);
        }
    }
}
