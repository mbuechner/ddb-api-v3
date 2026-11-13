package de.ddb.apiv3.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Shares one HTTP S3 fixture across cached application contexts, each using the real S3 client. */
@Import(S3TestSupport.FixtureConfiguration.class)
public abstract class S3TestSupport {

    protected static final S3TestServer STORAGE = new S3TestServer();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(STORAGE::close, "s3-test-server-shutdown"));
    }

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("ddb.storage.s3.endpoint", () -> STORAGE.endpoint().toString());
        registry.add("ddb.storage.s3.region", () -> "eu-central-1");
        registry.add("ddb.storage.s3.bucket", () -> S3TestServer.BUCKET);
        registry.add("ddb.storage.s3.access-key", () -> "test-access-key");
        registry.add("ddb.storage.s3.secret-key", () -> "test-secret-key");
        registry.add("ddb.storage.s3.path-style-access", () -> true);
        registry.add("ddb.storage.s3.create-bucket", () -> true);
        registry.add("ddb.storage.s3.connection-timeout", () -> "1s");
        registry.add("ddb.storage.s3.read-timeout", () -> "1s");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {

        @Bean(destroyMethod = "")
        S3TestServer s3TestServer() {
            return STORAGE;
        }
    }
}
