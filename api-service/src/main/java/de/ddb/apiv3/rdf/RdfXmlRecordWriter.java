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

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import org.apache.jena.graph.Graph;
import org.apache.jena.irix.IRIs;
import org.apache.jena.langtag.LangTags;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.impl.Util;
import org.apache.jena.rdfxml.xmloutput.impl.RDFXML_Basic;
import org.apache.jena.riot.RiotException;
import org.apache.jena.util.FileUtils;
import org.apache.jena.util.SplitIRI;
import org.apache.jena.util.XML10Char;
import org.apache.jena.vocabulary.RDF;
import org.unbescape.xml.XmlEscape;

/** Jena writes flat nodes directly; a usable rdf:type supplies each node's XML element name. */
final class RdfXmlRecordWriter extends RDFXML_Basic {

    private static final String RDF_NS = RDF.getURI();
    private static final Set<String> RESERVED_NODE_NAMES = Set.of(
            "RDF", "Description", "ID", "about", "parseType", "resource", "nodeID", "datatype",
            "li", "aboutEach", "aboutEachPrefix", "bagID");

    private final Map<Resource, String> types = new HashMap<>();

    // Jena's RIOT plain writer uses this deprecated constructor too.
    // Keep the exemption local: typed, flat nodes need the subclass hooks.
    @SuppressWarnings("deprecation")
    private RdfXmlRecordWriter(Model model) {
        model.listStatements(null, RDF.type, (RDFNode) null).forEachRemaining(statement -> {
            if (statement.getObject().isURIResource()) {
                String iri = statement.getResource().getURI();
                if (usableType(iri)) {
                    types.putIfAbsent(statement.getSubject(), iri);
                }
            }
        });
        int nextPrefix = 0;
        for (String iri : new HashSet<>(types.values())) {
            // This type becomes implicit, so retain the native writer's IRI check here.
            IRIs.checkEx(iri);
            String namespace = iri.substring(0, SplitIRI.splitXML(iri));
            if (model.getNsURIPrefix(namespace) == null) {
                String prefix;
                do {
                    prefix = "type" + nextPrefix++;
                } while (model.getNsPrefixURI(prefix) != null);
                model.setNsPrefix(prefix, namespace);
            }
        }
        // Keep XMLLiteral lexical content as escaped text, without interpreting its markup.
        setProperty("blockRules", "parseTypeLiteralPropertyElt");
        setProperty("showXmlDeclaration", true);
        setProperty("tab", 0);
    }

    static byte[] write(Graph graph) {
        Model model = ModelFactory.createModelForGraph(graph);
        var writer = new RdfXmlRecordWriter(model);
        var output = new ByteArrayOutputStream();
        writer.write(model, new BufferedWriter(FileUtils.asUTF8(output)), null);
        return output.toByteArray();
    }

    @Override
    protected void writeDescriptionHeader(Resource resource, PrintWriter output) {
        String type = types.get(resource);
        if (type == null) {
            super.writeDescriptionHeader(resource, output);
        } else {
            output.print("<" + startElementTag(type) + " ");
            writeResourceId(resource, output);
            output.println(">");
        }
    }

    @Override
    protected void writeDescriptionTrailer(Resource resource, PrintWriter output) {
        String type = types.get(resource);
        if (type == null) {
            super.writeDescriptionTrailer(resource, output);
        } else {
            output.println("</" + endElementTag(type) + ">");
        }
    }

    @Override
    protected void writePredicate(Statement statement, PrintWriter output) {
        if (statement.getPredicate().equals(RDF.type) && statement.getObject().isURIResource()
                && statement.getResource().getURI().equals(types.get(statement.getSubject()))) {
            return;
        }
        super.writePredicate(statement, output);
    }

    @Override
    protected void writeLiteral(Literal literal, PrintWriter output) {
        String lexicalForm = literal.getLexicalForm();
        checkXmlCharacters(lexicalForm);
        if (Util.isSimpleString(literal)) {
            output.print(">");
            // Attribute escaping also preserves CR: raw XML text would normalize it to LF.
            output.print(XmlEscape.escapeXml10AttributeMinimal(lexicalForm));
            return;
        }
        if (!literal.getLanguage().isEmpty()) {
            LangTags.requireValid(literal.getLanguage());
        }
        super.writeLiteral(literal, output);
    }

    @Override
    protected String substitutedAttribute(String value) {
        checkXmlCharacters(value);
        return attributeQuoted(XmlEscape.escapeXml10AttributeMinimal(value));
    }

    private static void checkXmlCharacters(String value) {
        if (value.codePoints().anyMatch(XML10Char::isInvalid)) {
            throw new RiotException("The RDF term cannot be represented in XML 1.0");
        }
    }

    private static boolean usableType(String iri) {
        int split = SplitIRI.splitXML(iri);
        if (split == 0 || split == iri.length()) {
            return false;
        }
        String namespace = iri.substring(0, split);
        String localName = iri.substring(split);
        return XML10Char.isValidNCName(localName) && !XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)
                && !(RDF_NS.equals(namespace) && RESERVED_NODE_NAMES.contains(localName));
    }
}
