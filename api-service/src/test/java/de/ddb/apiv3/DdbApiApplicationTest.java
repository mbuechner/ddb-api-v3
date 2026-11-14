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

package de.ddb.apiv3;

import static org.assertj.core.api.Assertions.assertThat;

import de.ddb.apiv3.storage.s3.S3RdfDocumentStore;
import de.ddb.apiv3.storage.s3.S3StorageProperties;
import de.ddb.apiv3.rdf.RdfCodec;
import de.ddb.apiv3.support.S3TestServer;
import de.ddb.apiv3.support.S3TestSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;

/** Confirms that storage survives recreating the adapter without an internal record model. */
@SpringBootTest
@ActiveProfiles("dev")
class DdbApiApplicationTest extends S3TestSupport {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private S3RdfDocumentStore repository;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3StorageProperties properties;

    @Autowired
    private RdfCodec codec;

    @Test
    void addressesTheSameStoredRdfThroughAnotherRepositoryInstanceWithoutADatasource() {
        String id = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        byte[] jelly = codec.toJelly(S3TestServer.DEMO_RDF,
                "https://api.deutsche-digitale-bibliothek.de/records/" + id);
        String originalEtag = repository.create(id, "record", jelly).orElseThrow();
        var recreatedRepository = new S3RdfDocumentStore(s3Client, properties);

        assertThat(recreatedRepository.create(id, "record", jelly)).isEmpty();
        assertThat(recreatedRepository.replace(id, "record", jelly, originalEtag)).isPresent();
        assertThat(recreatedRepository.read(id, "record").orElseThrow().asByteArray()).containsExactly(jelly);
        assertThat(STORAGE.storedRdf(id)).containsExactly(jelly);
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }
}
