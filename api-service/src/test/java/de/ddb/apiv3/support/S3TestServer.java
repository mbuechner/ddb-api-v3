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

package de.ddb.apiv3.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small HTTP fixture for the S3 operations used by the application. The real AWS client signs and
 * sends requests to this server; no application persistence bean is replaced. This is not a
 * substitute for the separate integration tests against SeaweedFS itself.
 */
public final class S3TestServer implements AutoCloseable {

    public static final String BUCKET = "ddb-api-test";
    public static final String DEMO_ID = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    public static final byte[] DEMO_RDF = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="https://example.org/record/demo-001">
                <rdf:type rdf:resource="http://www.europeana.eu/schemas/edm/ProvidedCHO"/>
              </rdf:Description>
            </rdf:RDF>
            """.getBytes(StandardCharsets.UTF_8);

    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<String> buckets = ConcurrentHashMap.newKeySet();
    private final Map<String, StoredObject> objects = new HashMap<>();
    private int writeCount;
    private volatile boolean available = true;
    private volatile Duration readBodyDelay = Duration.ZERO;

    public S3TestServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            buckets.add(BUCKET);
            server.start();
        } catch (IOException exception) {
            executor.close();
            throw new IllegalStateException("Cannot start S3 test server", exception);
        }
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    /** Delay after response headers, so tests catch timers that stop before reading S3's body. */
    public void setReadBodyDelay(Duration delay) {
        readBodyDelay = delay;
    }

    public synchronized int recordCount() {
        return objects.size();
    }

    public synchronized byte[] storedRdf(String recordId) {
        return storedObject(recordId + "/record");
    }

    public synchronized byte[] storedObject(String key) {
        StoredObject stored = objects.get(BUCKET + "/" + key);
        if (stored == null) {
            throw new IllegalArgumentException("No stored RDF object " + key);
        }
        return stored.body().clone();
    }

    public synchronized String storedContentType(String key) {
        StoredObject stored = objects.get(BUCKET + "/" + key);
        if (stored == null) {
            throw new IllegalArgumentException("No stored RDF object " + key);
        }
        return stored.contentType();
    }

    /** Bypasses the API so read tests can exercise corrupt objects or invalid storage formats. */
    public synchronized void storeObject(String key, byte[] body, String contentType) {
        put(BUCKET + "/" + key, body, contentType);
    }

    public synchronized Set<String> objectKeys() {
        return objects.keySet().stream().map(key -> key.substring(BUCKET.length() + 1))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public synchronized int writeCount() {
        return writeCount;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!available) {
                error(exchange, 503, "ServiceUnavailable");
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.startsWith("AWS4-HMAC-SHA256 ")) {
                error(exchange, 403, "AccessDenied");
                return;
            }
            String path = exchange.getRequestURI().getPath().substring(1);
            int separator = path.indexOf('/');
            String bucket = separator < 0 ? path : path.substring(0, separator);
            String key = separator < 0 ? "" : path.substring(separator + 1);
            String method = exchange.getRequestMethod();
            if (key.isEmpty()) {
                if ("PUT".equals(method)) {
                    readBody(exchange);
                    buckets.add(bucket);
                    exchange.sendResponseHeaders(200, -1);
                } else if (buckets.contains(bucket)) {
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    error(exchange, 404, "NoSuchBucket");
                }
                return;
            }
            if (!buckets.contains(bucket)) {
                error(exchange, 404, "NoSuchBucket");
                return;
            }
            byte[] requestBody = "PUT".equals(method) ? readBody(exchange) : null;
            synchronized (this) {
                StoredObject current = objects.get(bucket + "/" + key);
                if ("PUT".equals(method)) {
                    writeCount++;
                    String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
                    String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
                    if (("*".equals(ifNoneMatch) && current != null)
                            || (ifMatch != null && (current == null || !matches(ifMatch, current.etag())))) {
                        error(exchange, 412, "PreconditionFailed");
                        return;
                    }
                    StoredObject stored = put(bucket + "/" + key, requestBody,
                            exchange.getRequestHeaders().getFirst("Content-Type"));
                    exchange.getResponseHeaders().set("ETag", stored.etag());
                    exchange.sendResponseHeaders(200, -1);
                } else if ("GET".equals(method) || "HEAD".equals(method)) {
                    if (current == null) {
                        error(exchange, 404, "NoSuchKey");
                        return;
                    }
                    exchange.getResponseHeaders().set("ETag", current.etag());
                    exchange.getResponseHeaders().set("Content-Type", current.contentType());
                    exchange.getResponseHeaders().set("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME
                            .format(ZonedDateTime.parse("2026-01-01T12:00:00Z")));
                    exchange.sendResponseHeaders(200, "HEAD".equals(method) ? -1 : current.body().length);
                    if (!"HEAD".equals(method)) {
                        try {
                            Thread.sleep(readBodyDelay);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted S3 fixture transfer", exception);
                        }
                        exchange.getResponseBody().write(current.body());
                    }
                } else {
                    error(exchange, 405, "MethodNotAllowed");
                }
            }
        }
    }

    private StoredObject put(String key, byte[] body, String contentType) {
        StoredObject object = new StoredObject(body.clone(), '"' + hash("MD5", body) + '"',
                contentType == null ? "application/octet-stream" : contentType);
        objects.put(key, object);
        return object;
    }

    private static boolean matches(String condition, String etag) {
        return java.util.Arrays.stream(condition.split(","))
                .map(value -> value.trim()).anyMatch(value -> "*".equals(value) || value.equals(etag));
    }

    /** HTTP transfer decoding does not remove the additional AWS signed-payload chunk framing. */
    private static byte[] readBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String contentEncoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        if (contentEncoding == null || !contentEncoding.toLowerCase(Locale.ROOT).contains("aws-chunked")) {
            return body;
        }

        ByteArrayInputStream encoded = new ByteArrayInputStream(body);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(body.length);
        while (true) {
            String header = readChunkHeader(encoded);
            int extension = header.indexOf(';');
            String sizeText = (extension < 0 ? header : header.substring(0, extension)).trim();
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid AWS chunk size", exception);
            }
            if (size < 0) {
                throw new IOException("Negative AWS chunk size");
            }
            if (size == 0) {
                // Remaining bytes are optional checksum/signature trailers, not object content.
                break;
            }
            byte[] chunk = encoded.readNBytes(size);
            if (chunk.length != size || encoded.read() != '\r' || encoded.read() != '\n') {
                throw new IOException("Incomplete AWS payload chunk");
            }
            decoded.writeBytes(chunk);
        }

        String expectedLength = exchange.getRequestHeaders().getFirst("x-amz-decoded-content-length");
        if (expectedLength != null && decoded.size() != Long.parseLong(expectedLength)) {
            throw new IOException("AWS decoded content length does not match the payload");
        }
        return decoded.toByteArray();
    }

    private static String readChunkHeader(ByteArrayInputStream encoded) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        for (int next; (next = encoded.read()) >= 0;) {
            if (next == '\r') {
                if (encoded.read() != '\n') {
                    throw new IOException("Invalid AWS chunk header terminator");
                }
                return header.toString(StandardCharsets.US_ASCII);
            }
            header.write(next);
        }
        throw new IOException("Missing AWS chunk header terminator");
    }

    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        byte[] body = ("<Error><Code>" + code + "</Code><Message>Test storage response</Message></Error>")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, "HEAD".equals(exchange.getRequestMethod()) ? -1 : body.length);
        if (!"HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseBody().write(body);
        }
    }

    private static String hash(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private record StoredObject(byte[] body, String etag, String contentType) {
    }
}
