package jrxml6to7;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

/**
 * Very small "v6 DTD style" → "v7 element-kind style" JRXML converter.
 *
 * Constraints (per Ben):
 * - Root: no xsi:* or xsd/schemaLocation attributes. We keep only normal attributes
 *   (e.g., name, resourceBundle, pageWidth, etc.), but strip any namespace/schema
 *   attributes like xmlns, xmlns:*, xsi:*, and *schemaLocation.
 * - Text fields: convert legacy <textField>...</textField> blocks into JR 7
 *   <element kind="textField"> nodes with a single <expression> child.
 * - Variables: convert <variableExpression> into <expression>.
 * - Groups: convert <groupExpression> into <expression>.
 * - Do NOT move raw child nodes from legacy *Expression elements. Instead,
 *   collect textual content, trim it, and wrap it in a CDATA section in the
 *   new <expression>.
 * - Rename isBlankWhenNull → blankWhenNull on the converted text fields.
 */
public class Jrxml6To7Converter0
{
    public static void main(String[] args) throws Exception
    {
        if (args.length != 2)
        {
            System.err.println("Usage: Jrxml6To7Converter <inputDirOrFile> <outputDirOrFile>");
            System.exit(1);
        }

        Path inPath = Paths.get(args[0]);
        Path outPath = Paths.get(args[1]);

        if (Files.isDirectory(inPath))
        {
            if (!Files.exists(outPath))
            {
                Files.createDirectories(outPath);
            }
            else if (!Files.isDirectory(outPath))
            {
                throw new IllegalArgumentException("Output path must be a directory when input is a directory");
            }

            convertTree(inPath, outPath);
        }
        else
        {
            // Single file mode
            File outFile = outPath.toFile();
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists())
            {
                parent.mkdirs();
            }

            try (InputStream in = new FileInputStream(inPath.toFile());
                 OutputStream out = new FileOutputStream(outFile))
            {
                convert(in, out);
            }
        }
    }

    private static void convertTree(Path inDir, Path outDir) throws IOException
    {
        Files.walkFileTree(inDir, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
            {
                if (file.getFileName().toString().toLowerCase().endsWith(".jrxml"))
                {
                    Path relative = inDir.relativize(file);
                    Path target = outDir.resolve(relative);
                    Files.createDirectories(target.getParent());

                    try (InputStream in = new FileInputStream(file.toFile());
                         OutputStream out = new FileOutputStream(target.toFile()))
                    {
                        convert(in, out);
                    }
                    catch (Exception e)
                    {
                        throw new IOException("Failed to convert " + file + ": " + e.getMessage(), e);
                    }
                }
                else
                {
                    // Just copy non-JRXML files
                    Path relative = inDir.relativize(file);
                    Path target = outDir.resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void convert(InputStream in, OutputStream out) throws Exception
    {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setValidating(false);

        DocumentBuilder builder = dbf.newDocumentBuilder();

        // Kill any external DTD fetches by returning a dummy DTD.
        builder.setEntityResolver(new EntityResolver()
        {
            @Override
            public InputSource resolveEntity(String publicId, String systemId)
            {
                if (systemId != null && systemId.contains("jasperreport.dtd"))
                {
                    String dummy = "<!ELEMENT jasperReport ANY>";
                    return new InputSource(new StringReader(dummy));
                }
                return null;
            }
        });

        Document doc = builder.parse(in);

        // Drop the DOCTYPE node if present.
        DocumentType dt = doc.getDoctype();
        if (dt != null && dt.getParentNode() != null)
        {
            dt.getParentNode().removeChild(dt);
        }

        Element root = doc.getDocumentElement();
        if (root == null || !"jasperReport".equals(root.getTagName()))
        {
            throw new IllegalStateException("Root element is not <jasperReport>");
        }

        // Strip namespace / schemaLocation attributes from the root.
        ensureJasper7Namespace(root);

        // Convert legacy variable/group expressions before we walk elements.
        convertVariableExpressions(doc, root);
        convertGroupExpressions(doc, root);

        // Convert bands / detail / group headers and footers to element-kind form.
        convertElements(doc, root);

        // For any already-element-kind text fields, ensure a single <expression> child
        // and drop leftover <textFieldExpression> nodes.
        ensureAllTextFieldsHaveExpression(doc, root);

        // Finally, write the updated document.
        writeDocument(doc, out);
    }

    /**
     * Strip any namespace / schema-related attributes from the root element.
     * JasperReports 7.x JSON loading does not require an explicit XML namespace
     * or schemaLocation, and keeping the old ones can cause validation issues.
     */
    private static void ensureJasper7Namespace(Element root)
    {
        NamedNodeMap attrs = root.getAttributes();
        java.util.List<String> toRemove = new java.util.ArrayList<String>();

        for (int i = 0; i < attrs.getLength(); i++)
        {
            Node n = attrs.item(i);
            if (n.getNodeType() != Node.ATTRIBUTE_NODE)
            {
                continue;
            }

            String name = n.getNodeName();
            if ("xmlns".equals(name)
                || name.startsWith("xmlns:")
                || name.startsWith("xsi:")
                || "schemaLocation".equals(name)
                || name.endsWith(":schemaLocation"))
            {
                toRemove.add(name);
            }
        }

        for (String attrName : toRemove)
        {
            root.removeAttribute(attrName);
        }
    }

    /**
     * Convert legacy <variableExpression> children into JR 7 <expression> nodes.
     */
    private static void convertVariableExpressions(Document doc, Element root)
    {
        NodeList vars = root.getElementsByTagName("variable");
        for (int i = 0; i < vars.getLength(); i++)
        {
            Node node = vars.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE)
            {
                continue;
            }

            Element var = (Element) node;
            Element oldExpr = firstChild(var, "variableExpression");
            if (oldExpr == null)
            {
                continue;
            }

            Element expr = firstChild(var, "expression");
            if (expr == null)
            {
                expr = doc.createElement("expression");
                if (oldExpr.hasAttribute("class"))
                {
                    expr.setAttribute("class", oldExpr.getAttribute("class"));
                }

                String exprText = collectText(oldExpr);
                if (exprText != null && !exprText.isEmpty())
                {
                    expr.appendChild(doc.createCDATASection(exprText));
                }

                // Insert before <initialValueExpression> if present, otherwise before the legacy node.
                Element init = firstChild(var, "initialValueExpression");
                if (init != null)
                {
                    var.insertBefore(expr, init);
                }
                else
                {
                    var.insertBefore(expr, oldExpr);
                }
            }

            // Drop the legacy tag either way.
            var.removeChild(oldExpr);
        }
    }

    /**
     * Convert legacy <groupExpression> children into JR 7 <expression> nodes.
     */
    private static void convertGroupExpressions(Document doc, Element root)
    {
        NodeList groups = root.getElementsByTagName("group");
        for (int i = 0; i < groups.getLength(); i++)
        {
            Node node = groups.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE)
            {
                continue;
            }

            Element group = (Element) node;
            Element oldExpr = firstChild(group, "groupExpression");
            if (oldExpr == null)
            {
                continue;
            }

            Element expr = firstChild(group, "expression");
            if (expr == null)
            {
                expr = doc.createElement("expression");
                if (oldExpr.hasAttribute("class"))
                {
                    expr.setAttribute("class", oldExpr.getAttribute("class"));
                }

                String exprText = collectText(oldExpr);
                if (exprText != null && !exprText.isEmpty())
                {
                    expr.appendChild(doc.createCDATASection(exprText));
                }

                group.insertBefore(expr, oldExpr);
            }

            // Drop the legacy node now that we've migrated its content.
            group.removeChild(oldExpr);
        }
    }

    /**
     * Recursively convert legacy "visual" nodes (staticText, textField, line, rectangle)
     * into <element kind="..."> JR 7 nodes.
     */
    private static void convertElements(Document doc, Element parent)
    {
        Node child = parent.getFirstChild();
        while (child != null)
        {
            Node next = child.getNextSibling();

            if (child.getNodeType() == Node.ELEMENT_NODE)
            {
                Element elem = (Element) child;
                String tag = elem.getTagName();

                if ("staticText".equals(tag))
                {
                    convertStaticText(doc, elem);
                }
                else if ("textField".equals(tag))
                {
                    convertTextField(doc, elem);
                }
                else if ("line".equals(tag))
                {
                    convertGraphicElement(doc, elem, "line");
                }
                else if ("rectangle".equals(tag))
                {
                    convertGraphicElement(doc, elem, "rectangle");
                }
                else
                {
                    // Recurse into any other element types (bands, groups, etc.)
                    convertElements(doc, elem);
                }
            }

            child = next;
        }
    }

    // Utility: copy all attributes, with a few tweaks (namespace stripping, isBlankWhenNull -> blankWhenNull).
    private static void copyAttributes(Element src, Element dest)
    {
        NamedNodeMap attrs = src.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++)
        {
            Node node = attrs.item(i);
            if (node.getNodeType() != Node.ATTRIBUTE_NODE)
            {
                continue;
            }

            String name = node.getNodeName();
            String value = node.getNodeValue();

            // Skip namespace / schema attributes; they are handled on the root.
            if ("xmlns".equals(name)
                || name.startsWith("xmlns:")
                || name.startsWith("xsi:")
                || "schemaLocation".equals(name)
                || name.endsWith(":schemaLocation"))
            {
                continue;
            }

            // JR 7.x uses blankWhenNull instead of the legacy isBlankWhenNull.
            if ("isBlankWhenNull".equals(name))
            {
                name = "blankWhenNull";
            }

            // don't overwrite if already present (e.g., kind)
            if (!dest.hasAttribute(name))
            {
                dest.setAttribute(name, value);
            }
        }
    }

    private static Element firstChild(Element parent, String name)
    {
        Node child = parent.getFirstChild();
        while (child != null)
        {
            if (child.getNodeType() == Node.ELEMENT_NODE)
            {
                Element elem = (Element) child;
                if (name.equals(elem.getTagName()))
                {
                    return elem;
                }
            }
            child = child.getNextSibling();
        }
        return null;
    }

    private static void moveChildElementsByTag(Element src, Element dest, String tagName)
    {
        Node child = src.getFirstChild();
        while (child != null)
        {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.ELEMENT_NODE &&
                tagName.equals(((Element) child).getTagName()))
            {
                src.removeChild(child);
                dest.appendChild(child);
            }
            child = next;
        }
    }

    private static String collectText(Element element)
    {
        StringBuilder sb = new StringBuilder();
        Node child = element.getFirstChild();
        while (child != null)
        {
            short type = child.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE)
            {
                sb.append(child.getNodeValue());
            }
            child = child.getNextSibling();
        }
        return sb.toString().trim();
    }

    private static void convertStaticText(Document doc, Element staticText)
    {
        Document owner = staticText.getOwnerDocument();

        Element element = owner.createElement("element");
        element.setAttribute("kind", "staticText");

        // copy any attributes on <staticText> (e.g., pattern, isPrintRepeatedValues, etc.)
        copyAttributes(staticText, element);

        Element reportElement = firstChild(staticText, "reportElement");
        if (reportElement != null)
        {
            copyAttributes(reportElement, element);
            moveChildElementsByTag(reportElement, element, "printWhenExpression");
            moveChildElementsByTag(reportElement, element, "property");
        }

        Element textElement = firstChild(staticText, "textElement");
        if (textElement != null)
        {
            copyTextElementFormatting(element, textElement);
        }

        // Convert static text body.
        Element text = firstChild(staticText, "text");
        if (text != null)
        {
            Element expr = owner.createElement("text");
            String content = collectText(text);
            if (!content.isEmpty())
            {
                expr.appendChild(owner.createCDATASection(content));
            }
            element.appendChild(expr);
        }

        Node parent = staticText.getParentNode();
        parent.replaceChild(element, staticText);
    }

    private static void convertTextField(Document doc, Element textField)
    {
        Document owner = textField.getOwnerDocument();

        Element element = owner.createElement("element");
        element.setAttribute("kind", "textField");

        // Copy attributes defined on <textField> itself.
        copyAttributes(textField, element);

        // Pull layout and printWhenExpression/property from <reportElement>
        Element reportElement = firstChild(textField, "reportElement");
        if (reportElement != null)
        {
            copyAttributes(reportElement, element);
            moveChildElementsByTag(reportElement, element, "printWhenExpression");
            moveChildElementsByTag(reportElement, element, "property");
        }

        // Pull font/style info from <textElement>/<font>
        Element textElement = firstChild(textField, "textElement");
        if (textElement != null)
        {
            copyTextElementFormatting(element, textElement);
        }

        // Convert <textFieldExpression> → <expression> with text content only.
        Element oldExpr = firstChild(textField, "textFieldExpression");
        if (oldExpr != null)
        {
            Element expr = doc.createElement("expression");
            if (oldExpr.hasAttribute("class"))
            {
                expr.setAttribute("class", oldExpr.getAttribute("class"));
            }

            // Extract only the textual expression and re-inject it as CDATA.
            String exprText = collectText(oldExpr);
            if (exprText != null && !exprText.isEmpty())
            {
                expr.appendChild(doc.createCDATASection(exprText));
            }

            element.appendChild(expr);
        }

        // Replace in parent
        Node parent = textField.getParentNode();
        parent.replaceChild(element, textField);
    }

    private static void copyTextElementFormatting(Element element, Element textElement)
    {
        // textAlignment -> hTextAlign
        if (textElement.hasAttribute("textAlignment"))
        {
            String align = textElement.getAttribute("textAlignment");
            if ("Left".equalsIgnoreCase(align))
            {
                element.setAttribute("hTextAlign", "Left");
            }
            else if ("Right".equalsIgnoreCase(align))
            {
                element.setAttribute("hTextAlign", "Right");
            }
            else if ("Center".equalsIgnoreCase(align))
            {
                element.setAttribute("hTextAlign", "Center");
            }
            else if ("Justified".equalsIgnoreCase(align))
            {
                element.setAttribute("hTextAlign", "Justified");
            }
        }

        // verticalAlignment -> vTextAlign
        if (textElement.hasAttribute("verticalAlignment"))
        {
            String valign = textElement.getAttribute("verticalAlignment");
            if ("Top".equalsIgnoreCase(valign))
            {
                element.setAttribute("vTextAlign", "Top");
            }
            else if ("Middle".equalsIgnoreCase(valign))
            {
                element.setAttribute("vTextAlign", "Middle");
            }
            else if ("Bottom".equalsIgnoreCase(valign))
            {
                element.setAttribute("vTextAlign", "Bottom");
            }
        }

        // font
        Element font = firstChild(textElement, "font");
        if (font != null)
        {
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

    private static void convertGraphicElement(Document doc, Element graphic, String kind)
    {
        Document owner = graphic.getOwnerDocument();

        Element element = owner.createElement("element");
        element.setAttribute("kind", kind);

        // Copy attributes on line/rectangle (pen, etc.)
        copyAttributes(graphic, element);

        Element reportElement = firstChild(graphic, "reportElement");
        if (reportElement != null)
        {
            copyAttributes(reportElement, element);
        }

        Node parent = graphic.getParentNode();
        parent.replaceChild(element, graphic);
    }

    /**
     * After conversion, make sure every <element kind="textField"> has a single
     * <expression> child. If we still find legacy <textFieldExpression> tags,
     * migrate their textual content into a new <expression> and drop them.
     */
    private static void ensureAllTextFieldsHaveExpression(Document doc, Element parent)
    {
        Node child = parent.getFirstChild();
        while (child != null)
        {
            Node next = child.getNextSibling();

            if (child.getNodeType() == Node.ELEMENT_NODE)
            {
                Element elem = (Element) child;

                if ("element".equals(elem.getTagName()) &&
                    "textField".equals(elem.getAttribute("kind")))
                {
                    Element expr = firstChild(elem, "expression");
                    Element legacyExpr = firstChild(elem, "textFieldExpression");

                    if (expr == null && legacyExpr != null)
                    {
                        Element newExpr = doc.createElement("expression");
                        if (legacyExpr.hasAttribute("class"))
                        {
                            newExpr.setAttribute("class", legacyExpr.getAttribute("class"));
                        }

                        String exprText = collectText(legacyExpr);
                        if (exprText != null && !exprText.isEmpty())
                        {
                            newExpr.appendChild(doc.createCDATASection(exprText));
                        }

                        elem.insertBefore(newExpr, legacyExpr);
                        elem.removeChild(legacyExpr);
                    }
                    else if (expr != null && legacyExpr != null)
                    {
                        // If both exist, drop the legacy tag once we've migrated the text.
                        elem.removeChild(legacyExpr);
                    }
                }

                // Recurse into children
                ensureAllTextFieldsHaveExpression(doc, elem);
            }

            child = next;
        }
    }

    private static void writeDocument(Document doc, OutputStream out) throws Exception
    {
        TransformerFactory tf = TransformerFactory.newInstance();
        try
        {
            tf.setAttribute("indent-number", 2);
        }
        catch (IllegalArgumentException ignored)
        {
            // Some implementations don't support this, ignore.
        }

        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");

        transformer.transform(new DOMSource(doc), new StreamResult(out));
    }
}
