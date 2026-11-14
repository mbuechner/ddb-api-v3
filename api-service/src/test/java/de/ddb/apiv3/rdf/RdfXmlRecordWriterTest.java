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

package de.ddb.apiv3.rdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.GraphMemFactory;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.TextDirection;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class RdfXmlRecordWriterTest {

    private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String EX = "https://example.org/";

    @Test
    void writesTypedResourcesAndBlankNodesAtTheTopLevelWithoutLosingAdditionalTypes() throws Exception {
        Graph source = RDFParser.create().fromString("""
                @prefix ex: <https://example.org/> .
                ex:root a ex:Main, ex:Also ; ex:related ex:linked ; ex:detail _:detail .
                ex:linked a ex:Child ; ex:label "Verknüpft"@de .
                _:detail a ex:Detail ; ex:value "0042" .
                """).lang(Lang.TURTLE).toGraph();
        try {
            byte[] xml = RdfXmlRecordWriter.write(source);
            Document document = xml(xml);
            Element root = document.getDocumentElement();
            List<Element> resources = children(root);
            assertThat(resources).hasSize(3);
            assertThat(resources).allSatisfy(resource -> {
                assertThat(resource.getNamespaceURI()).isEqualTo(EX);
                assertThat(resource.getLocalName()).isIn("Main", "Also", "Child", "Detail");
            });
            Element related = (Element) document.getElementsByTagNameNS(EX, "related").item(0);
            Element detail = (Element) document.getElementsByTagNameNS(EX, "detail").item(0);
            assertThat(related.getAttributeNS(RDF, "resource")).isEqualTo(EX + "linked");
            assertThat(detail.hasAttributeNS(RDF, "nodeID")).isTrue();
            assertThat(children(related)).isEmpty();
            assertThat(children(detail)).isEmpty();
            assertRoundTrip(source, xml);
        } finally {
            source.close();
        }
    }

    @Test
    void preservesFallbackTypesAndSignificantXmlLiteralContent() throws Exception {
        Graph source = RDFParser.create().fromString("""
                @prefix ex: <https://example.org/> .
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                ex:reserved a rdf:Description ; ex:label "reserved" .
                ex:invalid a <https://example.org/123> ; ex:label "invalid QName" .
                ex:untyped ex:label "untyped" .
                ex:typed a ex:Main ;
                  ex:markup '<m:span xmlns:m="urn:markup">  a <m:em>b</m:em> c  </m:span>'^^rdf:XMLLiteral .
                """).lang(Lang.TURTLE).toGraph();
        try {
            byte[] xml = RdfXmlRecordWriter.write(source);
            Document document = xml(xml);
            assertThat(document.getElementsByTagNameNS(RDF, "Description").getLength()).isEqualTo(3);
            assertThat(document.getElementsByTagNameNS(EX, "Main").getLength()).isEqualTo(1);
            assertRoundTrip(source, xml);
        } finally {
            source.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"xml", "XML", "xmlType", "XmLType", "xmlns"})
    void replacesReservedPreferredPrefixesWithoutChangingTheRdf(String prefix) throws Exception {
        Graph source = RDFParser.create().fromString("""
                @prefix %s: <https://example.org/types/> .
                <https://example.org/record> a %s:Record .
                """.formatted(prefix, prefix)).lang(Lang.TURTLE).toGraph();
        try {
            byte[] output = RdfXmlRecordWriter.write(source);
            Element resource = children(xml(output).getDocumentElement()).getFirst();
            assertThat(resource.getNamespaceURI()).isEqualTo("https://example.org/types/");
            assertThat(resource.getLocalName()).isEqualTo("Record");
            assertThat(resource.getPrefix()).doesNotStartWithIgnoringCase("xml");
            assertRoundTrip(source, output);
        } finally {
            source.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<https://example.org/r> a <https://unknown.example/type/Record> .",
            "<urn:record:1> a <urn:types:Record> .",
            "<https://example.org/r> a <https://example.org/Record>; <https://example.org/p> '<span/>'^^<http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral> .",
            "<https://example.org/r> a <https://example.org/Record>; <https://example.org/p> '<span>a\\r\\nb</span>'^^<http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral> .",
            "<https://example.org/r> a <https://example.org/Record>; <https://example.org/p> \"\\\"<tag>&escaped>\\r\\n\\t\\U0001F600\" ."
    })
    void preservesPrefixlessTypesAndLiteralLexemes(String turtle) throws Exception {
        Graph source = RDFParser.create().fromString(turtle).lang(Lang.TURTLE).toGraph();
        try {
            byte[] output = RdfXmlRecordWriter.write(source);
            assertThat(children(xml(output).getDocumentElement()).getFirst().getLocalName()).isEqualTo("Record");
            assertRoundTrip(source, output);
        } finally {
            source.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0001", "\uD800", "\uDFFF", "\uFFFE"})
    void rejectsCharactersThatXml10CannotRepresent(String character) {
        Graph source = GraphMemFactory.createDefaultGraph();
        source.add(Triple.create(NodeFactory.createURI(EX + "record"), NodeFactory.createURI(EX + "label"),
                NodeFactory.createLiteralString("text" + character)));
        try {
            assertThatThrownBy(() -> RdfXmlRecordWriter.write(source)).isInstanceOf(RuntimeException.class);
        } finally {
            source.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"resource", "namespace", "language", "languageQuote"})
    void rejectsUnrepresentableXmlAttributes(String field) {
        Graph source = GraphMemFactory.createDefaultGraph();
        var subject = NodeFactory.createURI(EX + "record" + (field.equals("resource") ? "\u0001" : ""));
        var predicate = NodeFactory.createURI(EX + (field.equals("namespace") ? "ns\u0001/label" : "label"));
        var literal = switch (field) {
            case "language" -> NodeFactory.createLiteralLang("text", "de\u0001");
            case "languageQuote" -> NodeFactory.createLiteralLang("text", "de\"");
            default -> NodeFactory.createLiteralString("text");
        };
        source.add(Triple.create(subject, predicate, literal));
        try {
            assertThatThrownBy(() -> RdfXmlRecordWriter.write(source)).isInstanceOf(RuntimeException.class);
        } finally {
            source.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://bad path/Type", "http://[broken/Type", "https://example.org/ns\u0001/Type"})
    void validatesTheTypeIriBeforeMakingItImplicit(String iri) {
        Graph source = GraphMemFactory.createDefaultGraph();
        source.add(Triple.create(NodeFactory.createURI(EX + "record"), NodeFactory.createURI(RDF + "type"),
                NodeFactory.createURI(iri)));
        try {
            assertThatThrownBy(() -> RdfXmlRecordWriter.write(source)).isInstanceOf(RuntimeException.class);
        } finally {
            source.close();
        }
    }

    @Test
    void preservesDirectionalLanguageLiterals() {
        Graph source = GraphMemFactory.createDefaultGraph();
        source.add(Triple.create(NodeFactory.createURI(EX + "record"), NodeFactory.createURI(EX + "label"),
                NodeFactory.createLiteralDirLang("\u0645\u0631\u062D\u0628\u0627", "ar", TextDirection.RTL)));
        try {
            assertRoundTrip(source, RdfXmlRecordWriter.write(source));
        } finally {
            source.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "quotes\"' <tag>&escaped>",
            "whitespace\tCR\rLF\nCRLF\r\nend",
            "CDATA terminator ]]> stays text",
            "Unicode \u00FC \uD83D\uDE00 and XML-valid controls \u007F\u0085\u009F"
    })
    void preservesPlainStringEscapingAndWhitespace(String lexicalForm) {
        Graph source = GraphMemFactory.createDefaultGraph();
        source.add(Triple.create(NodeFactory.createURI(EX + "record"), NodeFactory.createURI(EX + "label"),
                NodeFactory.createLiteralString(lexicalForm)));
        try {
            assertRoundTrip(source, RdfXmlRecordWriter.write(source));
        } finally {
            source.close();
        }
    }

    private static void assertRoundTrip(Graph source, byte[] xml) {
        Graph parsed = RDFParser.create().source(new ByteArrayInputStream(xml)).lang(Lang.RDFXML).toGraph();
        try {
            assertThat(parsed.isIsomorphicWith(source)).isTrue();
        } finally {
            parsed.close();
        }
    }

    private static Document xml(byte[] bytes) throws Exception {
        DocumentBuilderFactory parser = DocumentBuilderFactory.newInstance();
        parser.setNamespaceAware(true);
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return parser.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }
}
