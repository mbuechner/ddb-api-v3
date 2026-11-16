/* Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek. Apache License, Version 2.0. */
package de.ddb.apiv3.api.items;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.ddb.apiv3.rdf.RdfCodec;
import de.ddb.apiv3.api.records.RecordShaclValidator;
import de.ddb.apiv3.support.S3TestServer;
import de.ddb.apiv3.support.S3TestSupport;
import eu.neverblink.jelly.convert.jena.riot.JellyLanguage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** Items reuse the proven records lifecycle, but own their key, graph, revision header and validator. */
@SpringBootTest(properties = {"ddb.api.max-document-size=1KB", "ddb.api.base-url=https://api.example.org"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@WithMockUser(authorities = {"SCOPE_records:write", "SCOPE_items:write"})
class ItemsApiContractTest extends S3TestSupport {
    private static final byte[] RDF = S3TestServer.DEMO_RDF;
    private static final byte[] UPDATED = new String(RDF, StandardCharsets.UTF_8)
            .replace("ProvidedCHO", "WebResource").getBytes(StandardCharsets.UTF_8);
    private static final String EMPTY = "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"/>";
    @Autowired
    private MockMvc mvc;
    @MockitoSpyBean
    private ItemShaclValidator items;
    @MockitoSpyBean
    private RecordShaclValidator records;

    @Test
    void createsReadsAndUpdatesItemsWithoutChangingTheRecordForTheSameId() throws Exception {
        String id = id();
        mvc.perform(post("/3/records/" + id).contentType("application/rdf+xml").content(RDF))
                .andExpect(status().isCreated());
        byte[] originalRecord = STORAGE.storedObject(id + "/record");
        String etag = create(id);
        assertThat(STORAGE.objectKeys()).contains(id + "/record", id + "/item");
        assertThat(STORAGE.storedContentType(id + "/item")).isEqualTo(RdfCodec.JELLY_CONTENT_TYPE);

        var response = mvc.perform(get(path(id))).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/ld+json"))
                .andExpect(header().string("X-Item-ETag", etag))
                .andExpect(header().doesNotExist("X-Record-ETag"))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("https://api.example.org/items/" + id);
        String weak = response.getHeader("ETag");
        assertThat(weak).isEqualTo("W/" + etag);
        mvc.perform(get(path(id)).header("If-None-Match", weak)).andExpect(status().isNotModified())
                .andExpect(header().string("X-Item-ETag", etag)).andExpect(content().string(""));
        mvc.perform(get(path(id)).accept("application/rdf+xml")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/rdf+xml"));
        byte[] jelly = mvc.perform(get(path(id)).accept(RdfCodec.JELLY_CONTENT_TYPE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var dataset = DatasetFactory.create();
        try {
            RDFDataMgr.read(dataset, new ByteArrayInputStream(jelly), JellyLanguage.JELLY);
            assertThat(dataset.containsNamedModel("https://api.example.org/items/" + id)).isTrue();
            assertThat(dataset.getNamedModel("https://api.example.org/items/" + id).size()).isEqualTo(1);
        } finally {
            dataset.close();
        }

        mvc.perform(post(path(id)).contentType("application/rdf+xml").content(UPDATED))
                .andExpect(status().isConflict());
        mvc.perform(put(path(id)).contentType("application/rdf+xml").content(UPDATED))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put(path(id)).header("If-Match", weak).contentType("application/rdf+xml").content(UPDATED))
                .andExpect(status().isPreconditionFailed());
        var updated = mvc.perform(put(path(id)).header("If-Match", etag)
                        .contentType("application/rdf+xml").content(UPDATED))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("ETag"))
                .andExpect(header().exists("X-Item-ETag")).andReturn().getResponse();
        assertThat(updated.getHeader("X-Item-ETag")).isNotEqualTo(etag);
        mvc.perform(put(path(id)).header("If-Match", etag).contentType("application/rdf+xml").content(RDF))
                .andExpect(status().isPreconditionFailed());
        assertThat(STORAGE.storedObject(id + "/record")).isEqualTo(originalRecord);
        mvc.perform(get(path(id))).andExpect(status().isOk())
                .andExpect(header().string("X-Item-ETag", updated.getHeader("X-Item-ETag")));
    }

    @Test
    void rejectsEmptyGraphsOnCreateAndUpdateBeforeStorage() throws Exception {
        String existing = id();
        String etag = create(existing);
        int writes = STORAGE.writeCount();
        mvc.perform(post(path(id())).contentType("application/rdf+xml").content(EMPTY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("RDF data does not conform to the items SHACL shapes"));
        mvc.perform(put(path(existing)).header("If-Match", etag).contentType("application/rdf+xml").content(EMPTY))
                .andExpect(status().isUnprocessableContent());
        assertThat(STORAGE.writeCount()).isEqualTo(writes);
        mvc.perform(get(path(existing))).andExpect(status().isOk()).andExpect(header().string("X-Item-ETag", etag));
    }

    @Test
    void keepsEndpointPoliciesSeparateAndNeverValidatesReads() throws Exception {
        String existing = id();
        String etag = create(existing);
        clearInvocations(items, records);
        doThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Future item-specific constraint"))
                .when(items).validate(any());
        int writes = STORAGE.writeCount();
        mvc.perform(post(path(id())).contentType("application/rdf+xml").content(RDF))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(put(path(existing)).header("If-Match", etag).contentType("application/rdf+xml").content(UPDATED))
                .andExpect(status().isUnprocessableContent());
        assertThat(STORAGE.writeCount()).isEqualTo(writes);
        mvc.perform(post("/3/records/" + id()).contentType("application/rdf+xml").content(RDF))
                .andExpect(status().isCreated());
        // Even a subsequently stricter item policy must not invalidate or slow down existing GETs.
        mvc.perform(get(path(existing))).andExpect(status().isOk());
        verify(items, times(2)).validate(any());
        verify(records, times(1)).validate(any());
    }

    @Test
    void enforcesSharedInputLimitsAndPreservesRedirectsAndWritePreconditions() throws Exception {
        int writes = STORAGE.writeCount();
        mvc.perform(get("/3/items/not-a-ddb-id")).andExpect(status().isNotFound());
        mvc.perform(post("/3/items/not-a-ddb-id").contentType("application/rdf+xml").content(RDF))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path(id())).contentType("application/rdf+xml").content("<broken"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post(path(id())).contentType("application/rdf+xml").content(new byte[1025]))
                .andExpect(status().isContentTooLarge());
        mvc.perform(post(path(id())).contentType("text/turtle").content("<urn:s> <urn:p> <urn:o> ."))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(STORAGE.writeCount()).isEqualTo(writes);
        String missing = id();
        mvc.perform(put(path(missing)).header("If-Match", "*").contentType("application/rdf+xml").content(RDF))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(get("/items/" + id() + "?format=rdf")).andExpect(status().isPermanentRedirect());
        assertThat(STORAGE.objectKeys()).doesNotContain(missing + "/item");
    }

    private String create(String id) throws Exception {
        return mvc.perform(post(path(id)).contentType("application/rdf+xml").content(RDF))
                .andExpect(status().isCreated()).andExpect(header().string("Location", path(id)))
                .andExpect(header().exists("X-Request-Id")).andReturn().getResponse().getHeader("ETag");
    }

    private static String path(String id) {
        return "/3/items/" + id;
    }

    private static String id() {
        // Map hexadecimal nibbles to A-P, a subset of the DDB identifier alphabet.
        return UUID.randomUUID().toString().replace("-", "").chars()
                .mapToObj(c -> String.valueOf((char) ('A' + Character.digit(c, 16)))).collect(Collectors.joining());
    }
}
