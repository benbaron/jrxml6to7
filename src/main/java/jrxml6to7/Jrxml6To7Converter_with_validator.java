package jrxml6to7;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Convert legacy JasperReports 6-style JRXML to JasperReports 7.x "element kind" syntax,
 * using a corpus-derived validator model (jr7_corpus_elements_attributes.json).
 *
 * Usage:
 *   Jrxml6To7Converter <inputDir> <outputDir> [--debug|-v]
 *                    [--validate] [--validate-strict] [--fail-on-validation]
 *                    [--corpus <path-to-jr7_corpus_elements_attributes.json>]
 */
public class Jrxml6To7Converter_with_validator
{
    /** Enable verbose debug logging with -v/--debug or -Djrxml6to7.debug=true. */
    private static boolean DEBUG = Boolean.getBoolean("jrxml6to7.debug");

    private static void debug(String msg)
    {
        if (DEBUG)
        {
            System.err.println("[jrxml6to7] " + msg);
        }
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length < 2)
        {
            System.err.println("Usage: Jrxml6To7Converter <inputDir> <outputDir> [--debug|-v] [--validate] [--validate-strict] [--fail-on-validation] [--corpus <path>]");
            System.exit(1);
        }

        Path inDir = Paths.get(args[0]);
        Path outDir = Paths.get(args[1]);

        boolean validate = false;
        boolean validateStrict = false;
        boolean failOnValidation = false;
        Path corpusPath = null;

        for (int i = 2; i < args.length; i++)
        {
            String a = args[i];
            if ("-v".equals(a) || "--debug".equals(a))
            {
                DEBUG = true;
            }
            else if ("--validate".equals(a))
            {
                validate = true;
            }
            else if ("--validate-strict".equals(a))
            {
                validate = true;
                validateStrict = true;
            }
            else if ("--fail-on-validation".equals(a))
            {
                failOnValidation = true;
            }
            else if ("--corpus".equals(a))
            {
                if (i + 1 >= args.length)
                {
                    throw new IllegalArgumentException("--corpus requires a path argument");
                }
                corpusPath = Paths.get(args[++i]);
            }
            else
            {
                throw new IllegalArgumentException("Unknown argument: " + a);
            }
        }

        if (!Files.isDirectory(inDir))
        {
            System.err.println("Input directory does not exist or is not a directory: " + inDir);
            System.exit(1);
        }
        Files.createDirectories(outDir);

        CorpusModel corpus = null;
        if (validate)
        {
            corpus = loadCorpusModel(corpusPath);
            if (corpus == null)
            {
                System.err.println("WARNING: validation enabled but corpus model could not be loaded; continuing without validation.");
                validate = false;
            }
        }

        debug("Input:  " + inDir.toAbsolutePath());
        debug("Output: " + outDir.toAbsolutePath());
        if (validate)
        {
            debug("Validation: " + (validateStrict ? "strict" : "normal") + (failOnValidation ? " (fail-on-validation)" : ""));
            debug("Corpus: " + corpus.getSourceDescription());
        }

        convertTree(inDir, outDir, corpus, validateStrict, failOnValidation);
    }

    private static CorpusModel loadCorpusModel(Path corpusPath)
    {
        // 1) explicit --corpus
        if (corpusPath != null)
        {
            return CorpusModel.tryLoad(corpusPath);
        }

        // 2) system property
        String sys = System.getProperty("jrxml6to7.corpus");
        if (sys != null && !sys.trim().isEmpty())
        {
            return CorpusModel.tryLoad(Paths.get(sys.trim()));
        }

        // 3) same working directory
        Path wd = Paths.get("").toAbsolutePath().resolve("jr7_corpus_elements_attributes.json");
        if (Files.isRegularFile(wd))
        {
            return CorpusModel.tryLoad(wd);
        }

        // 4) classpath resource
        try (InputStream in = Jrxml6To7Converter_with_validator.class.getClassLoader().getResourceAsStream("jr7_corpus_elements_attributes.json"))
        {
            if (in != null)
            {
                return CorpusModel.tryLoad(in, "classpath:jr7_corpus_elements_attributes.json");
            }
        }
        catch (IOException ex)
        {
            // ignore; return null below
        }

        return null;
    }

    private static void convertTree(Path inputDir, Path outputDir, CorpusModel corpus, boolean strictValidation, boolean failOnValidation)
        throws IOException
    {
        Files.walkFileTree(
            inputDir,
            EnumSet.noneOf(FileVisitOption.class),
            Integer.MAX_VALUE,
            new SimpleFileVisitor<Path>()
            {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
                {
                    Path rel = inputDir.relativize(dir);
                    Path targetDir = outputDir.resolve(rel);
                    Files.createDirectories(targetDir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
                {
                    Path rel = inputDir.relativize(file);
                    Path out = outputDir.resolve(rel);

                    if (file.toString().toLowerCase(Locale.ROOT).endsWith(".jrxml"))
                    {
                        debug("Converting " + file + " -> " + out);

                        Files.createDirectories(out.getParent());

                        try (InputStream in = Files.newInputStream(file);
                             OutputStream os = Files.newOutputStream(out))
                        {
                            convertSingle(in, os, file.toString(), corpus, strictValidation, failOnValidation);
                        }
                        catch (Exception ex)
                        {
                            System.err.println("Failed to convert " + file + ": " + ex.getMessage());
                            ex.printStackTrace(System.err);
                        }
                    }
                    else
                    {
                        Files.createDirectories(out.getParent());
                        Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
                    }

                    return FileVisitResult.CONTINUE;
                }
            }
        );
    }

    private static void convertSingle(
        InputStream in,
        OutputStream out,
        String debugName,
        CorpusModel corpus,
        boolean strictValidation,
        boolean failOnValidation
    )
        throws IOException, ParserConfigurationException, SAXException, TransformerException
    {
        Document doc = parseXmlIgnoringExternalDtd(in, debugName);

        // Drop any DOCTYPE that may have been created.
        DocumentType doctype = doc.getDoctype();
        if (doctype != null && doctype.getParentNode() != null)
        {
            doctype.getParentNode().removeChild(doctype);
        }

        Element root = doc.getDocumentElement();
        if (root == null)
        {
            throw new IllegalStateException("No document element in " + debugName);
        }

        // Per your request: strip xmlns/xsi/*schemaLocation from root.
        stripRootDecorations(root);

        // Convert legacy expression tags to JR 7 tags.
        convertVariables(doc, root);       // <variableExpression> -> <expression>
        convertGroupExpressions(doc, root); // <groupExpression> -> <expression>

        // Convert visual nodes to <element kind="..."> form.
        convertElements(doc, root);

        // Final sanity fixes.
        ensureAllTextFieldsHaveExpression(doc, root);

        // Optional validator stage.
        if (corpus != null)
        {
            ValidationResult vr = JrxmlValidator.validate(doc, corpus, strictValidation);

            if (!vr.issues.isEmpty())
            {
                System.err.println("Validation issues for " + debugName + " (" + vr.countErrors() + " errors, " + vr.countWarnings() + " warnings):");
                for (ValidationIssue iss : vr.issues)
                {
                    System.err.println("  - " + iss.severity + " " + iss.path + ": " + iss.message);
                }
            }

            if (failOnValidation && vr.countErrors() > 0)
            {
                throw new IllegalStateException("Validation failed (" + vr.countErrors() + " errors) for " + debugName);
            }
        }

        writeDocument(doc, out);
    }

    private static Document parseXmlIgnoringExternalDtd(InputStream in, String debugName)
        throws ParserConfigurationException, SAXException, IOException
    {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setValidating(false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);

        DocumentBuilder builder = dbf.newDocumentBuilder();
        builder.setEntityResolver(new EntityResolver()
        {
            @Override
            public InputSource resolveEntity(String publicId, String systemId) throws SAXException, IOException
            {
                if (systemId != null && systemId.contains("jasperreport.dtd"))
                {
                    debug("Ignoring external DTD " + systemId + " for " + debugName);

                    // Minimal, well-formed stub.
                    return new InputSource(new StringReader("<!ELEMENT jasperReport ANY>"));
                }
                return null; // default processing
            }
        });

        return builder.parse(in);
    }

    /**
     * Strip xmlns / xsi:* / *schemaLocation attributes off the root.
     * Keep all non-namespace report attributes (name, resourceBundle, pageWidth, etc.).
     */
    private static void stripRootDecorations(Element root)
    {
        // Remove xmlns declarations and any schema location attributes.
        List<String> toRemove = new ArrayList<>();

        NamedNodeMap attrs = root.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++)
        {
            Node n = attrs.item(i);
            if (n.getNodeType() != Node.ATTRIBUTE_NODE)
            {
                continue;
            }

            String name = n.getNodeName();
            String lname = name.toLowerCase(Locale.ROOT);

            if ("xmlns".equals(name) || name.startsWith("xmlns:") || name.startsWith("xsi:"))
            {
                toRemove.add(name);
            }
            else if (lname.contains("schemalocation"))
            {
                toRemove.add(name);
            }
        }

        for (String a : toRemove)
        {
            root.removeAttribute(a);
        }
    }

    private static void writeDocument(Document doc, OutputStream out)
        throws TransformerException
    {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.transform(new DOMSource(doc), new StreamResult(out));
    }

    /**
     * v6: <variableExpression>...</variableExpression>
     * v7: <expression>...</expression>
     *
     * IMPORTANT: JR 7 expression elements in your v7 corpus have no attributes (including no "class").
     */
    private static void convertVariables(Document doc, Element root)
    {
        NodeList vars = root.getElementsByTagName("variable");

        for (int i = 0; i < vars.getLength(); i++)
        {
            Node n = vars.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE)
            {
                continue;
            }

            Element var = (Element) n;
            Element varExpr = firstChildElement(var, "variableExpression");
            if (varExpr == null)
            {
                continue;
            }

            String exprText = extractExpressionText(varExpr);
            Element expr = doc.createElement("expression");
            if (!exprText.isEmpty())
            {
                expr.appendChild(doc.createCDATASection(exprText));
            }

            var.replaceChild(expr, varExpr);

            debug("Converted <variableExpression> to <expression> on variable " + var.getAttribute("name"));
        }
    }

    /**
     * Convert all <groupExpression> inside <group> to <expression>.
     */
    private static void convertGroupExpressions(Document doc, Element root)
    {
        NodeList groups = root.getElementsByTagName("group");

        for (int i = 0; i < groups.getLength(); i++)
        {
            Node n = groups.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE)
            {
                continue;
            }

            Element group = (Element) n;
            Element ge = firstChildElement(group, "groupExpression");
            if (ge == null)
            {
                continue;
            }

            String exprText = extractExpressionText(ge);
            Element expr = doc.createElement("expression");
            if (!exprText.isEmpty())
            {
                expr.appendChild(doc.createCDATASection(exprText));
            }

            group.replaceChild(expr, ge);
            debug("Converted <groupExpression> to <expression> on group " + group.getAttribute("name"));
        }
    }

    /**
     * Extract expression text from any legacy *Expression element.
     *
     * Fix: Do NOT move raw nodes. Rebuild as a single CDATA node.
     * This avoids JR 7 Jackson mapping issues when attributes or mixed nodes exist.
     */
    private static String extractExpressionText(Element legacyExprEl)
    {
        if (legacyExprEl == null)
        {
            return "";
        }

        String txt = legacyExprEl.getTextContent();
        if (txt == null)
        {
            return "";
        }

        // Normalize whitespace but preserve the core expression.
        // We trim outer whitespace; internal whitespace/newlines are kept.
        return txt.trim();
    }

    /**
     * Convert v6-style visual nodes to JR 7 element-kind nodes.
     */
    private static void convertElements(Document doc, Element parent)
    {
        Node child = parent.getFirstChild();

        while (child != null)
        {
            Node next = child.getNextSibling();

            if (child.getNodeType() == Node.ELEMENT_NODE)
            {
                Element el = (Element) child;
                String tag = el.getTagName();

                if ("staticText".equals(tag))
                {
                    convertStaticText(doc, el);
                }
                else if ("textField".equals(tag))
                {
                    convertTextField(doc, el);
                }
                else if ("line".equals(tag))
                {
                    convertGraphicElement(doc, el, "line");
                }
                else if ("rectangle".equals(tag))
                {
                    convertGraphicElement(doc, el, "rectangle");
                }
                else if ("frame".equals(tag))
                {
                    convertFrameElement(doc, el);
                }
                else if ("image".equals(tag))
                {
                    convertImageElement(doc, el);
                }
                else if ("subreport".equals(tag))
                {
                    convertSubreportElement(doc, el);
                }
                else if ("elementGroup".equals(tag))
                {
                    // In most of your corpus, elementGroup is only a wrapper.
                    flattenElementGroup(el);
                }
                else
                {
                    // Recurse into children.
                    convertElements(doc, el);
                }
            }

            child = next;
        }
    }

    private static void convertStaticText(Document doc, Element staticText)
    {
        Element element = doc.createElement("element");
        element.setAttribute("kind", "staticText");

        copyAttributes(staticText, element);

        Element reportElement = firstChildElement(staticText, "reportElement");
        if (reportElement != null)
        {
            copyAttributes(reportElement, element);
            moveChildElementsByTag(reportElement, element, "printWhenExpression");
            moveChildElementsByTag(reportElement, element, "property");
        }

        Element textElement = firstChildElement(staticText, "textElement");
        if (textElement != null)
        {
            copyTextElementFormatting(textElement, element);
        }

        Element textNode = firstChildElement(staticText, "text");
        if (textNode != null)
        {
            staticText.removeChild(textNode);
            element.appendChild(textNode);
        }
        else
        {
            String literal = collectDirectText(staticText);
            if (!literal.isEmpty())
            {
                Element t = doc.createElement("text");
                t.appendChild(doc.createCDATASection(literal));
                element.appendChild(t);
            }
        }

        moveRemainingChildren(staticText, element, reportElement, textElement, textNode);

        Node parent = staticText.getParentNode();
        parent.replaceChild(element, staticText);
    }

    private static void convertTextField(Document doc, Element textField)
    {
        Element element = doc.createElement("element");
        element.setAttribute("kind", "textField");

        // Copy attributes from <textField> itself. Translate known v6 boolean naming.
        copyAttributes(textField, element);

        // v6: isBlankWhenNull -> v7: blankWhenNull
        if (element.hasAttribute("isBlankWhenNull") && !element.hasAttribute("blankWhenNull"))
        {
            element.setAttribute("blankWhenNull", element.getAttribute("isBlankWhenNull"));
            element.removeAttribute("isBlankWhenNull");
        }

        Element reportElement = firstChildElement(textField, "reportElement");
        if (reportElement != null)
        {
            copyAttributes(reportElement, element);
            moveChildElementsByTag(reportElement, element, "printWhenExpression");
            moveChildElementsByTag(reportElement, element, "property");
        }

        Element textElement = firstChildElement(textField, "textElement");
        if (textElement != null)
        {
            copyTextElementFormatting(textElement, element);
        }

        Element oldExpr = firstChildElement(textField, "textFieldExpression");
        if (oldExpr != null)
        {
            String exprText = extractExpressionText(oldExpr);
            Element expr = doc.createElement("expression");
            if (!exprText.isEmpty())
            {
                expr.appendChild(doc.createCDATASection(exprText));
            }

            // Remove the old node and attach the new.
            textField.removeChild(oldExpr);
            element.appendChild(expr);
        }

        moveRemainingChildren(textField, element, reportElement, textElement, oldExpr);

        Node parent = textField.getParentNode();
        parent.replaceChild(element, textField);
    }

    private static void convertGraphicElement(Document doc, Element graphic, String kind)
    {
        Element element = doc.createElement("element");
        element.setAttribute("kind", kind);

        copyAttributes(graphic, element);

        Element reportElement = firstChildElement(graphic, "reportElement");
        if (reportElement != null)
        {
            copyAttributes(reportElement, element);
            moveChildElementsByTag(reportElement, element, "printWhenExpression");
            moveChildElementsByTag(reportElement, element, "property");
        }

        // Drop <graphicElement> wrapper (JR 7 element-kind uses direct attributes like fill/backcolor, etc.)
        Node child = graphic.getFirstChild();
        while (child != null)
        {
            Node next = child.getNextSibling();

            if (child == reportElement)
            {
                graphic.removeChild(child);
            }
            else if (child.getNodeType() == Node.ELEMENT_NODE && "graphicElement".equals(((Element) child).getTagName()))
            {
                graphic.removeChild(child);
            }
            else
            {
                graphic.removeChild(child);
                element.appendChild(child);
            }

            child = next;
        }

        Node parent = graphic.getParentNode();
        parent.replaceChild(element, graphic);
    }

    private static void convertFrameElement(Document doc, Element frame)
    {
        Element element = doc.createElement("element");
        element.setAttribute("kind", "frame");

        copyAttributes(frame, element);

        Element reportElement = firstChildElement(frame, "reportElement");
        if (reportElement != null)
        {
            copyAttributes(reportElement, element);
            moveChildElementsByTag(reportElement, element, "printWhenExpression");
            moveChildElementsByTag(reportElement, element, "property");
        }

        Node child = frame.getFirstChild();
        while (child != null)
        {
            Node next = child.getNextSibling();
            if (child == reportElement)
            {
                frame.removeChild(child);
            }
            else
            {
                frame.removeChild(child);
                element.appendChild(child);
            }
            child = next;
        }

        Node parent = frame.getParentNode();
        parent.replaceChild(element, frame);

        // Convert nested contents too.
        convertElements(doc, element);
    }

    /**
     * Convert v6 <image> to v7 <element kind="image">.
     * For now we preserve children like <imageExpression> by turning into <expression>.
     */
    private static void convertImageElement(Document doc, Element image)
    {
        Element element = doc.createElement("element");
        element.setAttribute("kind", "image");

        copyAttributes(image, element);

        Element reportElement = firstChildElement(image, "reportElement");
        if (reportElement != null)
        {
            copyAttributes(reportElement, element);
            moveChildElementsByTag(reportElement, element, "printWhenExpression");
            moveChildElementsByTag(reportElement, element, "property");
        }

        // v6: <imageExpression> -> v7: <expression>
        Element imgExpr = firstChildElement(image, "imageExpression");
        if (imgExpr != null)
        {
            String exprText = extractExpressionText(imgExpr);
            Element expr = doc.createElement("expression");
            if (!exprText.isEmpty())
            {
                expr.appendChild(doc.createCDATASection(exprText));
            }
            image.removeChild(imgExpr);
            element.appendChild(expr);
        }

        moveRemainingChildren(image, element, reportElement, imgExpr);

        Node parent = image.getParentNode();
        parent.replaceChild(element, image);
    }

    /**
     * Convert v6 <subreport> to v7 <element kind="subreport">.
     *
     * IMPORTANT: In your v7 corpus, <expression> elements have no attributes.
     */
    private static void convertSubreportElement(Document doc, Element subreport)
    {
        Element parent = (Element) subreport.getParentNode();
        Element reportElement = firstChildElement(subreport, "reportElement");
        Element subreportExpr = firstChildElement(subreport, "subreportExpression");

        Element element = doc.createElement("element");
        element.setAttribute("kind", "subreport");

        copyAttributes(subreport, element);

        // v6: isUsingCache -> v7: usingCache
        if (element.hasAttribute("isUsingCache") && !element.hasAttribute("usingCache"))
        {
            element.setAttribute("usingCache", element.getAttribute("isUsingCache"));
            element.removeAttribute("isUsingCache");
        }

        if (reportElement != null)
        {
            copyAttributes(reportElement, element);
            moveChildElementsByTag(reportElement, element, "printWhenExpression");
            moveChildElementsByTag(reportElement, element, "property");
        }

        // Keep these (already recognized in v7 corpus where present).
        moveChildElementsByTag(subreport, element, "parametersMapExpression");
        moveChildElementsByTag(subreport, element, "dataSourceExpression");
        moveChildElementsByTag(subreport, element, "connectionExpression");
        moveChildElementsByTag(subreport, element, "returnValue");

        // Convert <subreportParameter> to <parameter> with <expression>.
        Node child = subreport.getFirstChild();
        while (child != null)
        {
            Node next = child.getNextSibling();

            if (child.getNodeType() == Node.ELEMENT_NODE && "subreportParameter".equals(((Element) child).getTagName()))
            {
                Element oldParam = (Element) child;
                Element newParam = doc.createElement("parameter");

                if (oldParam.hasAttribute("name"))
                {
                    newParam.setAttribute("name", oldParam.getAttribute("name"));
                }

                Element oldExpr = firstChildElement(oldParam, "subreportParameterExpression");
                if (oldExpr != null)
                {
                    String exprText = extractExpressionText(oldExpr);
                    Element expr = doc.createElement("expression");
                    if (!exprText.isEmpty())
                    {
                        expr.appendChild(doc.createCDATASection(exprText));
                    }
                    newParam.appendChild(expr);
                }

                subreport.removeChild(oldParam);
                element.appendChild(newParam);
            }

            child = next;
        }

        // Convert <subreportExpression> to <expression>.
        if (subreportExpr != null)
        {
            String exprText = extractExpressionText(subreportExpr);
            Element expr = doc.createElement("expression");
            if (!exprText.isEmpty())
            {
                expr.appendChild(doc.createCDATASection(exprText));
            }
            // remove old node from original parent
            subreport.removeChild(subreportExpr);
            element.appendChild(expr);
        }

        // Move any remaining children we haven't consumed.
        moveRemainingChildren(subreport, element, reportElement);

        parent.replaceChild(element, subreport);

        // Convert nested items.
        convertElements(doc, element);
    }

    /**
     * Ensure all <element kind="textField"> have an <expression> child.
     * If the original v6 textField had no expression, we inject an empty one so the design loads.
     */
    private static void ensureAllTextFieldsHaveExpression(Document doc, Element root)
    {
        NodeList els = root.getElementsByTagName("element");

        for (int i = 0; i < els.getLength(); i++)
        {
            Node n = els.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE)
            {
                continue;
            }

            Element el = (Element) n;
            if (!"textField".equals(el.getAttribute("kind")))
            {
                continue;
            }

            Element expr = firstChildElement(el, "expression");
            if (expr == null)
            {
                Element newExpr = doc.createElement("expression");
                // Empty is allowed (will render blank), but we validate/warn.
                newExpr.appendChild(doc.createCDATASection(""));
                el.appendChild(newExpr);
            }
            else
            {
                // Sanitize: remove any attributes accidentally copied.
                NamedNodeMap attrs = expr.getAttributes();
                if (attrs != null && attrs.getLength() > 0)
                {
                    List<String> rm = new ArrayList<>();
                    for (int a = 0; a < attrs.getLength(); a++)
                    {
                        rm.add(attrs.item(a).getNodeName());
                    }
                    for (String a : rm)
                    {
                        expr.removeAttribute(a);
                    }
                }

                // Sanitize: if expression has element children, squash to plain text.
                String txt = expr.getTextContent();
                if (txt != null)
                {
                    txt = txt.trim();
                }
                else
                {
                    txt = "";
                }
                removeAllChildren(expr);
                expr.appendChild(doc.createCDATASection(txt));
            }
        }
    }

    private static void removeAllChildren(Element el)
    {
        Node c = el.getFirstChild();
        while (c != null)
        {
            Node next = c.getNextSibling();
            el.removeChild(c);
            c = next;
        }
    }

    /**
     * Flatten <elementGroup> by moving its children next to it.
     */
    private static void flattenElementGroup(Element group)
    {
        Node parent = group.getParentNode();
        if (parent == null)
        {
            return;
        }

        Node ref = group.getNextSibling();
        while (group.getFirstChild() != null)
        {
            Node child = group.getFirstChild();
            group.removeChild(child);
            parent.insertBefore(child, ref);
        }

        parent.removeChild(group);
    }

    // ----- small helpers ---------------------------------------------------

    private static Element firstChildElement(Element parent, String name)
    {
        Node child = parent.getFirstChild();
        while (child != null)
        {
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(((Element) child).getTagName()))
            {
                return (Element) child;
            }
            child = child.getNextSibling();
        }
        return null;
    }

    private static void moveChildElementsByTag(Element from, Element to, String tagName)
    {
        Node child = from.getFirstChild();
        while (child != null)
        {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(((Element) child).getTagName()))
            {
                from.removeChild(child);
                to.appendChild(child);
            }
            child = next;
        }
    }

    private static void moveRemainingChildren(Element from, Element to, Element... consumed)
    {
        Node child = from.getFirstChild();
        while (child != null)
        {
            Node next = child.getNextSibling();

            boolean skip = false;
            if (child.getNodeType() == Node.ELEMENT_NODE)
            {
                for (Element e : consumed)
                {
                    if (e != null && child == e)
                    {
                        skip = true;
                        break;
                    }
                }
            }

            from.removeChild(child);
            if (!skip)
            {
                to.appendChild(child);
            }

            child = next;
        }
    }

    /**
     * Copy attributes from one element to another with small v6->v7 normalizations:
     * - drop xmlns/xsi/*schemaLocation on non-root nodes too (defensive)
     * - v6 boolean "isXxx" becomes "xxx" when matching corpus (blankWhenNull, usingCache, etc.)
     */
    private static void copyAttributes(Element from, Element to)
    {
        NamedNodeMap attrs = from.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++)
        {
            Node n = attrs.item(i);
            if (n.getNodeType() != Node.ATTRIBUTE_NODE)
            {
                continue;
            }

            Attr a = (Attr) n;
            String name = a.getName();
            String value = a.getValue();

            String lname = name.toLowerCase(Locale.ROOT);
            if ("xmlns".equals(name) || name.startsWith("xmlns:") || name.startsWith("xsi:") || lname.contains("schemalocation"))
            {
                continue;
            }

            // Generic boolean-property normalization: isBlankWhenNull -> blankWhenNull, isUsingCache -> usingCache, etc.
            String normalizedName = normalizeBooleanIsPrefix(name);
            to.setAttribute(normalizedName, value);
        }
    }

    private static String normalizeBooleanIsPrefix(String name)
    {
        if (name != null && name.length() >= 3 && name.startsWith("is"))
        {
            char c = name.charAt(2);
            if (c >= 'A' && c <= 'Z')
            {
                String rest = name.substring(2);
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return name;
    }

    private static void copyTextElementFormatting(Element textElement, Element element)
    {
        if (textElement.hasAttribute("textAlignment"))
        {
            element.setAttribute("hTextAlign", textElement.getAttribute("textAlignment"));
        }
        if (textElement.hasAttribute("verticalAlignment"))
        {
            element.setAttribute("vTextAlign", textElement.getAttribute("verticalAlignment"));
        }
        if (textElement.hasAttribute("rotation"))
        {
            element.setAttribute("rotation", textElement.getAttribute("rotation"));
        }
        if (textElement.hasAttribute("markup"))
        {
            element.setAttribute("markup", textElement.getAttribute("markup"));
        }

        Element font = firstChildElement(textElement, "font");
        if (font != null)
        {
            if (font.hasAttribute("fontName"))
            {
                element.setAttribute("fontName", font.getAttribute("fontName"));
            }
            if (font.hasAttribute("size"))
            {
                element.setAttribute("fontSize", font.getAttribute("size"));
            }
            if ("true".equals(font.getAttribute("isBold")))
            {
                element.setAttribute("bold", "true");
            }
            if ("true".equals(font.getAttribute("isItalic")))
            {
                element.setAttribute("italic", "true");
            }
            if ("true".equals(font.getAttribute("isUnderline")))
            {
                element.setAttribute("underline", "true");
            }
            if ("true".equals(font.getAttribute("isStrikeThrough")))
            {
                element.setAttribute("strikeThrough", "true");
            }
        }
    }

    private static String collectDirectText(Element element)
    {
        StringBuilder sb = new StringBuilder();
        Node child = element.getFirstChild();
        while (child != null)
        {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
            {
                sb.append(child.getNodeValue());
            }
            child = child.getNextSibling();
        }
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------------
    // Validator stage (uses corpus JSON)
    // ---------------------------------------------------------------------

    public static final class ValidationIssue
    {
        public final Severity severity;
        public final String path;
        public final String message;

        public ValidationIssue(Severity severity, String path, String message)
        {
            this.severity = Objects.requireNonNull(severity, "severity");
            this.path = Objects.requireNonNull(path, "path");
            this.message = Objects.requireNonNull(message, "message");
        }
    }

    public enum Severity
    {
        ERROR,
        WARNING
    }

    public static final class ValidationResult
    {
        public final List<ValidationIssue> issues = new ArrayList<>();

        public int countErrors()
        {
            int c = 0;
            for (ValidationIssue i : this.issues)
            {
                if (i.severity == Severity.ERROR)
                {
                    c++;
                }
            }
            return c;
        }

        public int countWarnings()
        {
            int c = 0;
            for (ValidationIssue i : this.issues)
            {
                if (i.severity == Severity.WARNING)
                {
                    c++;
                }
            }
            return c;
        }
    }

    public static final class CorpusModel
    {
        private final String sourceDescription;
        private final Map<String, TagSpec> tags = new HashMap<>();
        private final Map<String, KindedTagSpec> kindedTags = new HashMap<>();

        private CorpusModel(String sourceDescription)
        {
            this.sourceDescription = sourceDescription;
        }

        public String getSourceDescription()
        {
            return this.sourceDescription;
        }

        public TagSpec getTagSpec(String tagName)
        {
            return this.tags.get(tagName);
        }

        public TagSpec getKindSpec(String tagName, String kind)
        {
            KindedTagSpec ks = this.kindedTags.get(tagName);
            if (ks == null)
            {
                return null;
            }
            return ks.kinds.get(kind);
        }

        public boolean isKindKnown(String tagName, String kind)
        {
            KindedTagSpec ks = this.kindedTags.get(tagName);
            return ks != null && ks.kinds.containsKey(kind);
        }

        public static CorpusModel tryLoad(Path path)
        {
            try (InputStream in = Files.newInputStream(path))
            {
                return tryLoad(in, path.toAbsolutePath().toString());
            }
            catch (IOException ex)
            {
                System.err.println("Failed to read corpus model at " + path + ": " + ex.getMessage());
                return null;
            }
        }

        public static CorpusModel tryLoad(InputStream in, String sourceDescription)
        {
            try
            {
                ObjectMapper om = new ObjectMapper();
                JsonNode root = om.readTree(in);

                CorpusModel cm = new CorpusModel(sourceDescription);

                JsonNode tagsNode = root.get("tags");
                if (tagsNode != null && tagsNode.isObject())
                {
                    tagsNode.fieldNames().forEachRemaining(name ->
                    {
                        JsonNode t = tagsNode.get(name);
                        TagSpec spec = TagSpec.fromJson(t);
                        cm.tags.put(name, spec);
                    });
                }

                JsonNode kindedNode = root.get("kinded_tags");
                if (kindedNode != null && kindedNode.isObject())
                {
                    kindedNode.fieldNames().forEachRemaining(tagName ->
                    {
                        JsonNode kt = kindedNode.get(tagName);
                        KindedTagSpec ks = KindedTagSpec.fromJson(kt);
                        cm.kindedTags.put(tagName, ks);
                    });
                }

                return cm;
            }
            catch (Exception ex)
            {
                System.err.println("Failed to parse corpus model (" + sourceDescription + "): " + ex.getMessage());
                return null;
            }
        }
    }

    public static final class TagSpec
    {
        public final Set<String> attributes = new HashSet<>();
        public final Set<String> children = new HashSet<>();

        static TagSpec fromJson(JsonNode node)
        {
            TagSpec s = new TagSpec();
            if (node == null || !node.isObject())
            {
                return s;
            }

            JsonNode a = node.get("attributes");
            if (a != null && a.isArray())
            {
                for (JsonNode x : a)
                {
                    if (x.isTextual())
                    {
                        s.attributes.add(x.asText());
                    }
                }
            }

            JsonNode c = node.get("child_elements");
            if (c == null)
            {
                c = node.get("children");
            }
            if (c != null && c.isArray())
            {
                for (JsonNode x : c)
                {
                    if (x.isTextual())
                    {
                        s.children.add(x.asText());
                    }
                }
            }

            return s;
        }
    }

    public static final class KindedTagSpec
    {
        public final Map<String, TagSpec> kinds = new HashMap<>();

        static KindedTagSpec fromJson(JsonNode node)
        {
            KindedTagSpec ks = new KindedTagSpec();
            if (node == null || !node.isObject())
            {
                return ks;
            }

            JsonNode kindsNode = node.get("kinds");
            if (kindsNode != null && kindsNode.isObject())
            {
                kindsNode.fieldNames().forEachRemaining(kind ->
                {
                    JsonNode specNode = kindsNode.get(kind);
                    TagSpec spec = TagSpec.fromJson(specNode);
                    ks.kinds.put(kind, spec);
                });
            }
            return ks;
        }
    }

    public static final class JrxmlValidator
    {
        public static ValidationResult validate(Document doc, CorpusModel corpus, boolean strict)
        {
            ValidationResult vr = new ValidationResult();
            Element root = doc.getDocumentElement();
            if (root == null)
            {
                vr.issues.add(new ValidationIssue(Severity.ERROR, "/", "No root element"));
                return vr;
            }

            // DFS walk with an explicit stack so we can track paths.
            Deque<NodeFrame> stack = new ArrayDeque<>();
            stack.push(new NodeFrame(root, "/" + root.getTagName(), 0));

            while (!stack.isEmpty())
            {
                NodeFrame f = stack.pop();
                Node n = f.node;

                if (n.getNodeType() != Node.ELEMENT_NODE)
                {
                    continue;
                }

                Element el = (Element) n;
                String tagName = el.getTagName();

                TagSpec spec = null;

                if ("element".equals(tagName))
                {
                    String kind = el.getAttribute("kind");
                    if (kind == null || kind.isEmpty())
                    {
                        vr.issues.add(new ValidationIssue(Severity.ERROR, f.path, "<element> missing required @kind"));
                    }
                    else
                    {
                        spec = corpus.getKindSpec("element", kind);
                        if (spec == null)
                        {
                            vr.issues.add(new ValidationIssue(Severity.ERROR, f.path, "<element> has unknown kind='" + kind + "'"));
                        }
                    }
                }
                else
                {
                    spec = corpus.getTagSpec(tagName);
                    if (spec == null)
                    {
                        vr.issues.add(new ValidationIssue(Severity.ERROR, f.path, "Unknown tag <" + tagName + "> (not in v7 corpus model)"));
                    }
                }

                // Validate attributes.
                if (spec != null)
                {
                    NamedNodeMap attrs = el.getAttributes();
                    for (int i = 0; i < attrs.getLength(); i++)
                    {
                        Node a = attrs.item(i);
                        if (a.getNodeType() != Node.ATTRIBUTE_NODE)
                        {
                            continue;
                        }
                        String an = a.getNodeName();

                        if (!spec.attributes.contains(an))
                        {
                            vr.issues.add(new ValidationIssue(strict ? Severity.ERROR : Severity.WARNING, f.path,
                                "Unknown attribute '" + an + "' on <" + tagName + ">" + kindSuffix(el)));
                        }
                    }
                }

                // Validate children tags.
                if (spec != null)
                {
                    Node c = el.getFirstChild();
                    int childIndex = 0;
                    while (c != null)
                    {
                        if (c.getNodeType() == Node.ELEMENT_NODE)
                        {
                            String cn = ((Element) c).getTagName();
                            if (!spec.children.contains(cn))
                            {
                                vr.issues.add(new ValidationIssue(strict ? Severity.ERROR : Severity.WARNING, f.path,
                                    "Unexpected child <" + cn + "> inside <" + tagName + ">" + kindSuffix(el)));
                            }
                            String childPath = f.path + "/" + cn + "[" + childIndex + "]";
                            stack.push(new NodeFrame(c, childPath, childIndex));
                            childIndex++;
                        }
                        c = c.getNextSibling();
                    }
                }
                else
                {
                    // still walk children to find nested unknowns
                    Node c = el.getFirstChild();
                    int childIndex = 0;
                    while (c != null)
                    {
                        if (c.getNodeType() == Node.ELEMENT_NODE)
                        {
                            String cn = ((Element) c).getTagName();
                            String childPath = f.path + "/" + cn + "[" + childIndex + "]";
                            stack.push(new NodeFrame(c, childPath, childIndex));
                            childIndex++;
                        }
                        c = c.getNextSibling();
                    }
                }

                // Extra rule: expression must not be empty (warning).
                if ("expression".equals(tagName))
                {
                    String t = el.getTextContent();
                    if (t == null || t.trim().isEmpty())
                    {
                        vr.issues.add(new ValidationIssue(Severity.WARNING, f.path, "<expression> is empty"));
                    }
                }
            }

            return vr;
        }

        private static String kindSuffix(Element el)
        {
            if ("element".equals(el.getTagName()))
            {
                String k = el.getAttribute("kind");
                if (k != null && !k.isEmpty())
                {
                    return " kind=\"" + k + "\"";
                }
            }
            return "";
        }

        private static final class NodeFrame
        {
            final Node node;
            final String path;
            final int index;

            NodeFrame(Node node, String path, int index)
            {
                this.node = node;
                this.path = path;
                this.index = index;
            }
        }
    }
}
