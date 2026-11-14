/* 
 *  Copyright 2026 Michael Büchner, Deutsche Nationalbibliothek
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *      https://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  
 *  This code was made with help from OpenAI GTP-6.1 Sol Ultra.
 */

package de.ddb.apiv3.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.ddb.apiv3.rdf.RdfCodec;
import de.ddb.apiv3.support.S3TestServer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.jena.riot.Lang;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;

/** Verifies real Jelly objects and atomic conditional writes against real SeaweedFS. */
class SeaweedFsRdfDocumentStoreIntegrationTest {

    private static final RdfCodec CODEC = new RdfCodec();
    private static GenericContainer<?> container;
    private static S3Client client;
    private static S3StorageProperties properties;
    private static S3RdfDocumentStore repository;

    @BeforeAll
    static void startStorage() {
        String endpoint = System.getProperty("seaweedfs.test.endpoint", "");
        String accessKey = System.getProperty("seaweedfs.test.access-key", "ddb-dev");
        String secretKey = System.getProperty("seaweedfs.test.secret-key", "ddb-dev-secret");
        if (endpoint.isBlank()) {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Real SeaweedFS requires Docker or -Dseaweedfs.test.endpoint");
            container = new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.53"));
            container.addEnv("AWS_ACCESS_KEY_ID", accessKey);
            container.addEnv("AWS_SECRET_ACCESS_KEY", secretKey);
            container.addEnv("DEBUG", "false");
            container.setCommand("mini", "-dir=/data", "-s3.port=8333",
                    "-webdav=false", "-admin.ui=false", "-master.telemetry=false");
            container.setExposedPorts(List.of(8333));
            container.setWaitStrategy(Wait.forListeningPort());
            container.start();
            endpoint = "http://" + container.getHost() + ":" + container.getMappedPort(8333);
        }
        properties = new S3StorageProperties(URI.create(endpoint), "eu-central-1",
                "ddb-api-integration-" + UUID.randomUUID(), accessKey, secretKey,
                true, 50, Duration.ofSeconds(2), Duration.ofSeconds(10), false);
        client = S3StorageConfiguration.s3Client(properties, S3StorageConfiguration.s3CredentialsProvider(properties));
        client.createBucket(request -> request.bucket(properties.bucket())
                .createBucketConfiguration(config -> config.locationConstraint(properties.region())));
        repository = new S3RdfDocumentStore(client, properties);
    }

    @AfterAll
    static void stopStorage() {
        try {
            if (client != null) {
                if (repository != null) {
                    client.listObjectsV2(request -> request.bucket(properties.bucket())).contents()
                            .forEach(object -> client.deleteObject(request -> request
                                    .bucket(properties.bucket()).key(object.key())));
                    client.deleteBucket(request -> request.bucket(properties.bucket()));
                }
                client.close();
            }
        } finally {
            if (container != null) {
                container.stop();
            }
        }
    }

    @Test
    void storesIndependentNamedDatasetsUnderExactlyTheDdbIdentifier() {
        String id = "J6KQ3UBFBEMGAHGR6ZUAQCUXOBKQCFO3";
        byte[] original = jelly(id, "demo-001");
        byte[] item = jelly(id, "item");

        String etag = repository.create(id, "record", original).orElseThrow();
        assertThat(repository.create(id, "item", item)).isPresent();
        var stored = repository.read(id, "record").orElseThrow();
        assertThat(stored.asByteArray()).containsExactly(original);
        assertThat(stored.response().eTag()).isEqualTo(etag);
        assertThat(stored.response().contentType()).isEqualTo("application/x-jelly-rdf");
        assertThat(stored.response().contentEncoding()).isNull();
        assertThat(payload(id, "item")).containsExactly(item);
        assertThat(client.listObjectsV2(request -> request.bucket(properties.bucket()).prefix(id + "/"))
                        .contents()).extracting(object -> object.key())
                .containsExactlyInAnyOrder(id + "/record", id + "/item");
        assertThat(client.headObject(request -> request.bucket(properties.bucket()).key(id + "/record"))
                        .contentType()).isEqualTo("application/x-jelly-rdf");
    }

    @Test
    void permitsExactlyOneConcurrentCreateForTheSameNamedDataset() throws Exception {
        String id = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            byte[] content = jelly(id, "creator-" + index);
            attempts.add(() -> repository.create(id, "record", content).isPresent());
        }

        assertThat(runTogether(attempts)).filteredOn(value -> value.booleanValue()).hasSize(1);
        assertThat(rdfXml(payload(id, "record"))).contains("creator-");
        assertThat(client.listObjectsV2(request -> request.bucket(properties.bucket()).prefix(id + "/"))
                        .contents()).extracting(object -> object.key()).containsExactly(id + "/record");
    }

    @Test
    void permitsExactlyOneConcurrentReplacementOfTheSameEtag() throws Exception {
        String id = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
        String initialEtag = repository.create(id, "record", jelly(id, "demo-001")).orElseThrow();
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            byte[] content = jelly(id, "replacement-" + index);
            attempts.add(() -> repository.replace(id, "record", content, initialEtag).isPresent());
        }

        assertThat(runTogether(attempts)).filteredOn(value -> value.booleanValue()).hasSize(1);
        assertThat(rdfXml(payload(id, "record"))).contains("replacement-");
    }

    @Test
    void preservesTheOtherDatasetAndRejectsAStaleEtagAcrossRepositoryInstances() {
        String id = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD";
        byte[] original = jelly(id, "demo-001");
        String initialEtag = repository.create(id, "record", original).orElseThrow();
        byte[] item = jelly(id, "independent-item");
        repository.create(id, "item", item).orElseThrow();
        var recreated = new S3RdfDocumentStore(client, properties);
        byte[] replacement = jelly(id, "updated");

        assertThat(recreated.replace(id, "record", replacement, "\"stale\"")).isEmpty();
        assertThat(payload(id, "record")).containsExactly(original);
        assertThat(recreated.replace(id, "record", replacement, initialEtag)).isPresent();
        assertThat(payload(id, "record")).containsExactly(replacement);
        assertThat(payload(id, "item")).containsExactly(item);
    }

    @Test
    void neverCreatesAMissingObjectThroughConditionalReplacement() {
        String id = "EEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE";

        assertThat(repository.replace(id, "record", jelly(id, "demo-001"), "*")).isEmpty();
        assertThat(repository.read(id, "record")).isEmpty();
        assertThat(client.listObjectsV2(request -> request.bucket(properties.bucket()).prefix(id + "/"))
                        .contents()).isEmpty();
    }

    private static List<Boolean> runTogether(List<Callable<Boolean>> operations) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> futures = operations.stream().map(operation -> executor.submit(() -> {
                start.await();
                return operation.call();
            })).toList();
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static byte[] payload(String recordId, String documentName) {
        return repository.read(recordId, documentName).orElseThrow().asByteArray();
    }

    private static byte[] jelly(String recordId, String value) {
        byte[] rdf = new String(S3TestServer.DEMO_RDF, StandardCharsets.UTF_8).replace("demo-001", value)
                .getBytes(StandardCharsets.UTF_8);
        return CODEC.toJelly(rdf,
                "https://api.deutsche-digitale-bibliothek.de/records/" + recordId);
    }

    private static String rdfXml(byte[] jelly) {
        return new String(CODEC.fromJelly(jelly, Lang.RDFXML), StandardCharsets.UTF_8);
    }
}
