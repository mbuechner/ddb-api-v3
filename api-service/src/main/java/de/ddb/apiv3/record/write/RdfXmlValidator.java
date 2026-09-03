/*
 * Copyright 2026 Michael Büchner, Deutsche Nationalbibliothek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This code was generated with assistance from OpenAI ChatGPT.
 */
package de.ddb.apiv3.record.write;

import java.io.ByteArrayInputStream;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.ErrorHandlerFactory;
import org.apache.jena.riot.system.StreamRDFLib;
import org.springframework.stereotype.Component;

/**
 * Performs the mandatory RDF/XML syntax check without retaining a second graph in memory.
 *
 * <p>The parser streams statements into a sink because this stage establishes only that the
 * complete upload is parseable RDF. It intentionally does not claim conformance to an application
 * profile; therefore a successfully uploaded record remains {@code NOT_VALIDATED} until the
 * planned semantic validation has run.</p>
 */
@Component
public final class RdfXmlValidator {

    /**
     * Parses the complete document with Jena's strict RDF/XML parser.
     *
     * @param rdfXml uploaded bytes
     * @param baseIri absolute base used to resolve relative RDF identifiers
     * @throws InvalidRdfException when XML parsing, RDF/XML parsing or strict RDF checks fail
     */
    public void validate(byte[] rdfXml, String baseIri) {
        try {
            RDFParser.create()
                    .source(new ByteArrayInputStream(rdfXml))
                    .lang(Lang.RDFXML)
                    .base(baseIri)
                    .strict(true)
                    .checking(true)
                    // Invalid client input becomes a 422 response and is not a server-side log error.
                    .errorHandler(ErrorHandlerFactory.errorHandlerStrictNoLogging)
                    .parse(StreamRDFLib.sinkNull());
        } catch (RiotException exception) {
            throw new InvalidRdfException(exception);
        }

        // TODO Add application-profile validation (for example SHACL) and further semantic checks.
    }
}
