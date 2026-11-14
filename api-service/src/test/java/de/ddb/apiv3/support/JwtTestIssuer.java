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

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Test-only issuer: private keys never leave this JVM; the API sees a real public JWKS. */
public final class JwtTestIssuer implements AutoCloseable {

    public static final String AUDIENCE = "ddb-write-security-test";

    private final RSAKey key;
    private final HttpServer server;
    private final AtomicInteger discoveryRequests = new AtomicInteger();
    private final AtomicInteger keyRequests = new AtomicInteger();

    public JwtTestIssuer() throws IOException, JOSEException {
        key = new RSAKeyGenerator(2048).keyID("writer-test-key").generate();
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/openid-configuration", exchange -> {
            discoveryRequests.incrementAndGet();
            byte[] metadata = ("{\"issuer\":\"" + issuerUri() + "\",\"jwks_uri\":\"" + jwkSetUri()
                    + "\",\"id_token_signing_alg_values_supported\":[\"RS256\"]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, metadata.length);
            exchange.getResponseBody().write(metadata);
            exchange.close();
        });
        server.createContext("/jwks", exchange -> {
            keyRequests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            exchange.getResponseBody().write(jwks);
            exchange.close();
        });
        server.start();
    }

    public String issuerUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String jwkSetUri() {
        return issuerUri() + "/jwks";
    }

    public int discoveryRequests() {
        return discoveryRequests.get();
    }

    public int keyRequests() {
        return keyRequests.get();
    }

    public JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(issuerUri()).audience(List.of(AUDIENCE))
                .subject("trusted-writer").issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", "records:write").claim("client_id", "writer-app");
    }

    public String token(Consumer<JWTClaimsSet.Builder> customize) throws JOSEException {
        var claims = claims();
        customize.accept(claims);
        return sign(claims.build(), key);
    }

    public String token() throws JOSEException {
        return token(_ -> { });
    }

    public static String sign(JWTClaimsSet claims, RSAKey key) throws JOSEException {
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
