/* Copyright 2026 Michael Buechner, Deutsche Nationalbibliothek. Apache License, Version 2.0. */
package de.ddb.apiv3.api.records;

import de.ddb.apiv3.documents.RdfDocumentService;
import de.ddb.apiv3.documents.RdfDocumentType;
import jakarta.validation.constraints.Pattern;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Explicit records routes; RDF, conditional requests and persistence share one lifecycle. */
@RestController
public final class RecordsController {
    private static final String PATH = "/3/records/{recordId}";
    private final RdfDocumentService documents;
    private final RdfDocumentType type;

    public RecordsController(RdfDocumentService documents, RecordShaclValidator validator) {
        this.documents = documents;
        this.type = new RdfDocumentType("records", "record", "X-Record-ETag", validator);
    }

    @PostMapping(path = PATH, consumes = "application/rdf+xml")
    public ResponseEntity<Void> createRecord(
            @PathVariable @Pattern(regexp = RdfDocumentService.DDB_ID) String recordId,
            @RequestBody InputStreamResource body, UriComponentsBuilder requestUri) {
        return documents.create(type, recordId, body, requestUri.build(true).toUri());
    }

    @PutMapping(path = PATH, consumes = "application/rdf+xml")
    public ResponseEntity<Void> putRecord(
            @PathVariable @Pattern(regexp = RdfDocumentService.DDB_ID) String recordId,
            @RequestBody InputStreamResource body,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            UriComponentsBuilder requestUri) {
        return documents.update(type, recordId, body, ifMatch, requestUri.build(true).toUri());
    }

    @GetMapping(path = PATH)
    public ResponseEntity<byte[]> getRecord(
            @PathVariable String recordId,
            @RequestHeader(name = HttpHeaders.ACCEPT, defaultValue = "application/ld+json") String accept,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        return documents.get(type, recordId, accept, ifNoneMatch);
    }
}
