package jrxml6to7;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Converts JasperReports 6.x JRXML (DTD / classic tags) into JasperReports 7.x "element-kind" JRXML.
 *
 * Key rules:
 *  - Convert <textField>/<staticText>/<line>/<rectangle>/<image>/<subreport>/<frame> to <element kind="...">.
 *  - Convert legacy *Expression tags (textFieldExpression, groupExpression, variableExpression, ...) to <expression>.
 *    The new <expression> contains a single CDATA node with the trimmed expression text.
 *  - Normalize legacy boolean attribute names (isBlankWhenNull -> blankWhenNull, isUsingCache -> usingCache, etc.).
 *  - Strip xmlns / xsi:* / *schemaLocation attributes from the root (user request).
 *  - Optional validation against a JR7 corpus model JSON.
 */
public final class Jrxml6To7Converter_patched2
{
    // ---------------- CLI / options ----------------

    private static final class Opts
    {
        Path inDir;
        Path outDir;

        boolean debug = false;
        boolean validate = false;
        boolean strict = false;
        boolean pretty = false;

        Path corpusPath = null; // file or directory
        Path logPath = null;
    }

    public static void main(String[] args) throws Exception
    {
        Opts opts = parseArgs(args);

        try (Log log = Log.open(opts.logPath, opts.debug))
        {
            CorpusModel corpus = null;
            if (opts.validate)
            {
                try
                {
                    corpus = CorpusModel.load(resolveCorpusPath(opts.corpusPath), log);
                    log.info("Loaded corpus model: " + corpus.sourcePath.toAbsolutePath());
                }
                catch (Exception e)
                {
                    log.warn("Validation requested but corpus model could not be loaded. Continuing without validation. Reason: " + e.getMessage());
                    if (opts.debug) e.printStackTrace(log.err());
                }
            }

            convertAll(opts.inDir, opts.outDir, opts, corpus, log);
        }
    }

    private static Opts parseArgs(String[] args)
    {
        if (args.length < 2)
        {
            usageAndExit();
        }

        Opts o = new Opts();
        o.inDir = Paths.get(args[0]);
        o.outDir = Paths.get(args[1]);

        for (int i = 2; i < args.length; i++)
        {
            String a = args[i];
            switch (a)
            {
                case "--debug":
                case "-v":
                    o.debug = true;
                    break;

                case "--validate":
                    o.validate = true;
                    break;

                case "--strict":
                    o.strict = true;
                    break;

                case "--pretty":
                    o.pretty = true;
                    break;

                case "--corpus":
                    if (i + 1 >= args.length) die("Missing value after --corpus");
                    o.corpusPath = Paths.get(args[++i]);
                    break;

                case "--log":
                    if (i + 1 >= args.length) die("Missing value after --log");
                    o.logPath = Paths.get(args[++i]);
                    break;

                default:
                    die("Unknown argument: " + a);
            }
        }

        return o;
    }

    private static void usageAndExit()
    {
        System.err.println("Usage: java -jar converter.jar <inputDir> <outputDir> [--debug] [--validate] [--corpus <path>] [--strict] [--pretty] [--log <file>]");
        System.err.println("  --validate            Validate converted output against a JR7 corpus model JSON (warnings by default)");
        System.err.println("  --corpus <path>       Path to jr7_corpus_elements_attributes.json OR a directory containing it (default: ./jr7_corpus_elements_attributes.json)");
        System.err.println("  --strict              Treat validator warnings as errors (non-zero exit if any)");
        System.err.println("  --pretty              Pretty-print output (default: compact; safer for <expression> parsing)");
        System.err.println("  --log <file>          Also write all converter output to this log file");
        System.exit(2);
    }

    private static void die(String msg)
    {
        System.err.println(msg);
        System.exit(2);
    }

    private static Path resolveCorpusPath(Path requested)
    {
        Path p = requested != null ? requested : Paths.get("jr7_corpus_elements_attributes.json");
        if (Files.isDirectory(p))
        {
            Path candidate = p.resolve("jr7_corpus_elements_attributes.json");
            if (Files.exists(candidate)) return candidate;
            // If they pointed at a directory, accept the first *.json file as a fallback.
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.json"))
            {
                for (Path f : ds)
                {
                    return f;
                }
            }
            catch (IOException ignored) {}
            throw new IllegalArgumentException("Corpus path is a directory, but no jr7_corpus_elements_attributes.json (or any *.json) found: " + p);
        }
        return p;
    }

    // ---------------- Conversion orchestration ----------------

    private static void convertAll(Path inDir, Path outDir, Opts opts, CorpusModel corpus, Log log) throws Exception
    {
        if (!Files.exists(inDir)) throw new FileNotFoundException("Input dir not found: " + inDir);
        Files.createDirectories(outDir);

        List<Path> inputs = new ArrayList<>();
        try (var walk = Files.walk(inDir))
        {
            walk.filter(p -> Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".jrxml"))
                .forEach(inputs::add);
        }

        log.info("Found " + inputs.size() + " .jrxml files under " + inDir.toAbsolutePath());

        int converted = 0;
        int warnCount = 0;
        int errCount = 0;

        for (Path inFile : inputs)
        {
            Path rel = inDir.relativize(inFile);
            Path outFile = outDir.resolve(rel);
            Files.createDirectories(outFile.getParent());

            log.info("Converting: " + rel);

            ConversionResult res = convertSingle(inFile, outFile, opts, corpus, log);
            converted++;

            warnCount += res.warnings;
            errCount += res.errors;
        }

        log.info("Done. Converted=" + converted + " warnings=" + warnCount + " errors=" + errCount);

        if (opts.strict && (warnCount > 0 || errCount > 0))
        {
            throw new RuntimeException("Strict mode: conversion produced warnings/errors: warnings=" + warnCount + " errors=" + errCount);
        }
        if (errCount > 0)
        {
            throw new RuntimeException("Conversion produced errors: " + errCount + ". See log for details.");
        }
    }

    private static final class ConversionResult
    {
        int warnings = 0;
        int errors = 0;
    }

    private static ConversionResult convertSingle(Path inFile, Path outFile, Opts opts, CorpusModel corpus, Log log) throws Exception
    {
        Document doc = parseXml(inFile);
        Element root = doc.getDocumentElement();

        // Root: strip namespaces & schema declarations; keep non-namespace attrs.
        stripRootNamespaces(root);

        // Perform in-place conversions.
        normalizeLegacyBooleanAttributes(doc.getDocumentElement());
        normalizeLegacyImageAlignAttributes(doc.getDocumentElement());
        normalizeLegacyStyleFontAttributes(doc.getDocumentElement());
        normalizeLegacyBoxBorderAttributes(doc, doc.getDocumentElement());
        convertVariables(root, doc, log);
        convertGroups(root, doc, log);
        convertAllElements(root, doc, log);

        // Remove legacy DOCTYPE if still present (we don't want DTD refs).
        removeDoctype(doc);

        // Optional validation of output.
        ConversionResult res = new ConversionResult();
        if (corpus != null)
        {
            JrxmlValidator v = new JrxmlValidator(corpus, log);
            ValidationReport vr = v.validate(doc);
            res.warnings = vr.warnings.size();
            res.errors = vr.errors.size();

            for (String w : vr.warnings) log.warn("[VALIDATE] " + relPathForLog(inFile) + ": " + w);
            for (String e : vr.errors)   log.error("[VALIDATE] " + relPathForLog(inFile) + ": " + e);
        }

        writeXml(doc, outFile, opts.pretty);
        return res;
    }

    private static String relPathForLog(Path p)
    {
        return p.getFileName().toString();
    }

    // ---------------- XML parse / write ----------------

    private static Document parseXml(Path file) throws Exception
    {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setExpandEntityReferences(false);
        // Attempt to prevent external entity resolution.
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}

        DocumentBuilder b = f.newDocumentBuilder();
        // Don't fetch external DTDs.
        b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        try (InputStream in = Files.newInputStream(file))
        {
            return b.parse(in);
        }
    }

    private static void writeXml(Document doc, Path outFile, boolean pretty) throws Exception
    {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.METHOD, "xml");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.setOutputProperty(OutputKeys.STANDALONE, "no");
        if (pretty)
        {
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            try { t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2"); } catch (Exception ignored) {}
        }
        else
        {
            // Compact output is safer: avoids pretty-printer whitespace inside <expression>.
            t.setOutputProperty(OutputKeys.INDENT, "no");
        }

        try (OutputStream out = Files.newOutputStream(outFile))
        {
            t.transform(new DOMSource(doc), new StreamResult(new OutputStreamWriter(out, StandardCharsets.UTF_8)));
        }
    }

    private static void removeDoctype(Document doc)
    {
        DocumentType dt = doc.getDoctype();
        if (dt == null) return;

        // DOM doesn't allow removing doctype directly; rebuild document without it if present.
        // Most parsers won't include it after entity resolver, but keep a no-op here for clarity.
    }

    // ---------------- Root namespace stripping ----------------

    private static void stripRootNamespaces(Element root)
    {
        // Remove xmlns*, xsi:* and schemaLocation attributes.
        NamedNodeMap attrs = root.getAttributes();
        List<String> toRemove = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++)
        {
            Node a = attrs.item(i);
            String n = a.getNodeName();
            if (n.equals("xmlns") || n.startsWith("xmlns:") || n.startsWith("xsi:") ||
                n.equals("schemaLocation") || n.endsWith(":schemaLocation") || n.equals("xsi:schemaLocation"))
            {
                toRemove.add(n);
            }
        }
        for (String n : toRemove) root.removeAttribute(n);
    }

    // ---------------- Legacy attribute normalization ----------------

    /**
     * Normalize legacy "isXxx" attributes anywhere they appear.
     * This runs on the full tree before element-kind conversion, so that later copyAttributes sees normalized names.
     */
    private static void normalizeLegacyBooleanAttributes(Element root)
    {
        NodeList all = root.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++)
        {
            Element e = (Element) all.item(i);

            renameAttributeIfPresent(e, "isBlankWhenNull", "blankWhenNull");
            renameAttributeIfPresent(e, "isPrintRepeatedValues", "printRepeatedValues");
            renameAttributeIfPresent(e, "isUsingCache", "usingCache");
            renameAttributeIfPresent(e, "isDefault", "default");

            if (e.hasAttribute("isSplitAllowed"))
            {
                String v = e.getAttribute("isSplitAllowed");
                e.removeAttribute("isSplitAllowed");
                // Jasper 7 uses band splitType. Most direct mapping:
                //  true  -> Stretch
                //  false -> Prevent
                if (!e.hasAttribute("splitType"))
                {
                    boolean b = "true".equalsIgnoreCase(v) || "1".equals(v);
                    e.setAttribute("splitType", b ? "Stretch" : "Prevent");
                }
            }
        }
    }


    private static void normalizeLegacyImageAlignAttributes(Element root)
    {
        // v6: <image hAlign="Center" vAlign="Middle">...</image>
        // v7 element-kind: <element kind="image" hImageAlign="Center" vImageAlign="Middle">...</element>
        NodeList imgs = root.getElementsByTagName("image");
        for (int i = 0; i < imgs.getLength(); i++)
        {
            Element img = (Element) imgs.item(i);
            renameAttributeIfPresent(img, "hAlign", "hImageAlign");
            renameAttributeIfPresent(img, "vAlign", "vImageAlign");
        }
    }

    private static void normalizeLegacyStyleFontAttributes(Element root)
    {
        // v6 style font: <font size="10" isBold="true" isItalic="false" .../>
        // v7 corpus uses: <font fontSize="10" bold="true" italic="false" .../>
        NodeList fonts = root.getElementsByTagName("font");
        for (int i = 0; i < fonts.getLength(); i++)
        {
            Element font = (Element) fonts.item(i);
            // Only rewrite the legacy "isX" attribute forms; keep already-normalized attributes as-is.
            renameAttributeIfPresent(font, "size", "fontSize");
            renameAttributeIfPresent(font, "isBold", "bold");
            renameAttributeIfPresent(font, "isItalic", "italic");
            renameAttributeIfPresent(font, "isUnderline", "underline");
            renameAttributeIfPresent(font, "isStrikeThrough", "strikeThrough");
        }
    }

    private static void normalizeLegacyBoxBorderAttributes(Document doc, Element root)
    {
        // v6: <box border="Thin" borderColor="#000000" topBorder="None" .../>
        // v7 corpus: <box ...><pen/><topPen/><leftPen/>...</box>
        NodeList boxes = root.getElementsByTagName("box");
        for (int i = 0; i < boxes.getLength(); i++)
        {
            Element box = (Element) boxes.item(i);
            boolean hasLegacy =
                box.hasAttribute("border") ||
                    box.hasAttribute("borderColor") ||
                    box.hasAttribute("topBorder") ||
                    box.hasAttribute("leftBorder") ||
                    box.hasAttribute("rightBorder") ||
                    box.hasAttribute("bottomBorder");

            if (!hasLegacy)
            {
                continue;
            }

            String borderColor = null;
            if (box.hasAttribute("borderColor"))
            {
                borderColor = box.getAttribute("borderColor");
                box.removeAttribute("borderColor");
            }

            // Create general <pen> if 'border' is present.
            if (box.hasAttribute("border"))
            {
                String border = box.getAttribute("border");
                box.removeAttribute("border");

                PenSpec pen = penFromLegacy(border, borderColor);
                if (pen != null)
                {
                    ensurePenChild(doc, box, "pen", pen);
                }
                else
                {
                    removeFirstChildElement(box, "pen");
                }
            }

            // Side-specific borders
            convertSideBorder(doc, box, "topBorder", "topPen", borderColor);
            convertSideBorder(doc, box, "leftBorder", "leftPen", borderColor);
            convertSideBorder(doc, box, "rightBorder", "rightPen", borderColor);
            convertSideBorder(doc, box, "bottomBorder", "bottomPen", borderColor);
        }
    }

    private static void convertSideBorder(Document doc, Element box, String attrName, String penTag, String lineColor)
    {
        if (!box.hasAttribute(attrName))
        {
            return;
        }

        String border = box.getAttribute(attrName);
        box.removeAttribute(attrName);

        PenSpec pen = penFromLegacy(border, lineColor);
        if (pen != null)
        {
            ensurePenChild(doc, box, penTag, pen);
        }
        else
        {
            // If it's explicitly "None", make sure we don't accidentally keep an existing pen.
            removeFirstChildElement(box, penTag);
        }
    }

    private static Element firstChildElement(Element parent, String tagName)
    {
        Node child = parent.getFirstChild();
        while (child != null)
        {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(((Element) child).getTagName()))
            {
                return (Element) child;
            }
            child = next;
        }
        return null;
    }

    private static void removeFirstChildElement(Element parent, String tagName)
    {
        Node child = parent.getFirstChild();
        while (child != null)
        {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(((Element) child).getTagName()))
            {
                parent.removeChild(child);
                return;
            }
            child = next;
        }
    }

    private static class PenSpec
    {
        final String lineWidth;
        final String lineColor;

        PenSpec(String lineWidth, String lineColor)
        {
            this.lineWidth = lineWidth;
            this.lineColor = lineColor;
        }
    }

    private static PenSpec penFromLegacy(String border, String lineColor)
    {
        if (border == null)
        {
            return null;
        }

        String b = border.trim();
        if (b.isEmpty() || "None".equalsIgnoreCase(b))
        {
            return null;
        }

        String lineWidth;
        // Common iReport values
        if ("Thin".equalsIgnoreCase(b))
        {
            lineWidth = "0.5";
        }
        else if ("1Point".equalsIgnoreCase(b) || "1".equals(b))
        {
            lineWidth = "1.0";
        }
        else if ("2Point".equalsIgnoreCase(b) || "2".equals(b))
        {
            lineWidth = "2.0";
        }
        else if ("4Point".equalsIgnoreCase(b) || "4".equals(b))
        {
            lineWidth = "4.0";
        }
        else
        {
            // Fallback: keep something visible
            lineWidth = "0.5";
        }

        return new PenSpec(lineWidth, (lineColor != null && !lineColor.isBlank()) ? lineColor : null);
    }

    private static void ensurePenChild(Document doc, Element box, String penTag, PenSpec pen)
    {
        Element existing = firstChildElement(box, penTag);
        Element penEl = existing != null ? existing : doc.createElement(penTag);

        if (pen.lineWidth != null)
        {
            penEl.setAttribute("lineWidth", pen.lineWidth);
        }
        if (pen.lineColor != null)
        {
            penEl.setAttribute("lineColor", pen.lineColor);
        }

        if (existing == null)
        {
            // Insert pen elements at the start of the box for stable ordering.
            Node first = box.getFirstChild();
            if (first != null)
            {
                box.insertBefore(penEl, first);
            }
            else
            {
                box.appendChild(penEl);
            }
        }
    }

    private static void renameAttributeIfPresent(Element e, String from, String to)
    {
        if (!e.hasAttribute(from)) return;
        String v = e.getAttribute(from);
        e.removeAttribute(from);
        if (!e.hasAttribute(to)) e.setAttribute(to, v);
    }

    // ---------------- Variables / Groups ----------------

    private static void convertVariables(Element root, Document doc, Log log)
    {
        NodeList vars = root.getElementsByTagName("variable");
        // Note: NodeList is live; collect first.
        List<Element> list = new ArrayList<>();
        for (int i = 0; i < vars.getLength(); i++) list.add((Element) vars.item(i));

        for (Element var : list)
        {
            Element varExpr = firstChildElementByTag(var, "variableExpression");
            if (varExpr != null)
            {
                String exprText = extractTrimmedText(varExpr);
                Element newExpr = createExpressionElement(doc, exprText);
                var.replaceChild(newExpr, varExpr);
            }

            Element initExpr = firstChildElementByTag(var, "initialValueExpression");
            if (initExpr != null)
            {
                normalizeExpressionContainer(initExpr, doc);
            }
        }
    }

    private static void convertGroups(Element root, Document doc, Log log)
    {
        NodeList groups = root.getElementsByTagName("group");
        List<Element> list = new ArrayList<>();
        for (int i = 0; i < groups.getLength(); i++) list.add((Element) groups.item(i));

        for (Element g : list)
        {
            Element ge = firstChildElementByTag(g, "groupExpression");
            if (ge != null)
            {
                String exprText = extractTrimmedText(ge);
                Element newExpr = createExpressionElement(doc, exprText);
                g.replaceChild(newExpr, ge);
            }
        }
    }

    // ---------------- Element-kind conversion ----------------

    private static void convertAllElements(Element root, Document doc, Log log)
    {
        // Convert in-place by walking the tree and converting matching elements.
        convertElementsRecursive(root, doc, log);
    }

    private static void convertElementsRecursive(Node node, Document doc, Log log)
    {
        if (node.getNodeType() != Node.ELEMENT_NODE)
        {
            // We aggressively drop whitespace-only text nodes to reduce mixed content surprises.
            if (node.getNodeType() == Node.TEXT_NODE)
            {
                if (node.getTextContent().trim().isEmpty())
                {
                    node.getParentNode().removeChild(node);
                }
            }
            return;
        }

        Element e = (Element) node;

        // Depth-first: copy children first (safe because we may replace current node).
        List<Node> children = childNodesSnapshot(e);
        for (Node c : children)
        {
            convertElementsRecursive(c, doc, log);
        }

        String tag = e.getTagName();
        switch (tag)
        {
            case "staticText":
                replaceWithElementKind(e, doc, "staticText", log);
                break;

            case "textField":
                replaceWithElementKind(e, doc, "textField", log);
                break;

            case "line":
                replaceWithElementKind(e, doc, "line", log);
                break;

            case "rectangle":
                replaceWithElementKind(e, doc, "rectangle", log);
                break;

            case "image":
                replaceWithElementKind(e, doc, "image", log);
                break;

            case "subreport":
                replaceWithElementKind(e, doc, "subreport", log);
                break;

            case "frame":
                replaceWithElementKind(e, doc, "frame", log);
                break;

            case "elementGroup":
                // Flatten elementGroup by moving its children up into parent, then remove it.
                flattenElementGroup(e);
                break;

            default:
                // nothing
                break;
        }
    }

    private static List<Node> childNodesSnapshot(Node n)
    {
        NodeList nl = n.getChildNodes();
        List<Node> out = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) out.add(nl.item(i));
        return out;
    }

    private static void flattenElementGroup(Element eg)
    {
        Node parent = eg.getParentNode();
        if (parent == null) return;
        List<Node> kids = childNodesSnapshot(eg);
        for (Node k : kids)
        {
            eg.removeChild(k);
            parent.insertBefore(k, eg);
        }
        parent.removeChild(eg);
    }

    private static void replaceWithElementKind(Element legacy, Document doc, String kind, Log log)
    {
        Element ek = doc.createElement("element");
        ek.setAttribute("kind", kind);

        // Copy reportElement into flattened attributes.
        Element reportElement = firstChildElementByTag(legacy, "reportElement");
        if (reportElement != null)
        {
            copyAttributeIfPresent(reportElement, ek, "x");
            copyAttributeIfPresent(reportElement, ek, "y");
            copyAttributeIfPresent(reportElement, ek, "width");
            copyAttributeIfPresent(reportElement, ek, "height");
            copyAttributeIfPresent(reportElement, ek, "backcolor");
            copyAttributeIfPresent(reportElement, ek, "forecolor");
            copyAttributeIfPresent(reportElement, ek, "mode");
            copyAttributeIfPresent(reportElement, ek, "style");
            copyAttributeIfPresent(reportElement, ek, "styleNameReference");
            copyAttributeIfPresent(reportElement, ek, "printWhenExpression");
            // keep uuid if present
            copyAttributeIfPresent(reportElement, ek, "uuid");
        }

        // Copy some common legacy attributes from the element itself
        copyAttributesNormalized(legacy, ek);

        // Special handling per kind:
        if ("staticText".equals(kind))
        {
            convertStaticTextContents(legacy, ek, doc);
        }
        else if ("textField".equals(kind))
        {
            convertTextFieldContents(legacy, ek, doc);
        }
        else if ("image".equals(kind))
        {
            convertImageContents(legacy, ek, doc);
        }
        else if ("subreport".equals(kind))
        {
            convertSubreportContents(legacy, ek, doc);
        }
        else if ("frame".equals(kind))
        {
            // Frame contains other elements; keep converted children
            moveConvertedChildren(legacy, ek);
        }
        else
        {
            // line / rectangle etc: no special children to keep (reportElement already flattened)
        }

        // Replace in parent.
        Node parent = legacy.getParentNode();
        if (parent != null)
        {
            parent.replaceChild(ek, legacy);
        }
    }

    private static void copyAttributeIfPresent(Element src, Element dst, String name)
    {
        if (src.hasAttribute(name))
        {
            dst.setAttribute(name, src.getAttribute(name));
        }
    }

    private static void copyAttributesNormalized(Element legacy, Element dst)
    {
        NamedNodeMap attrs = legacy.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++)
        {
            Node a = attrs.item(i);
            String name = a.getNodeName();
            String value = a.getNodeValue();

            // Skip namespace decls
            if (name.equals("xmlns") || name.startsWith("xmlns:") || name.startsWith("xsi:")) continue;

            // The element-kind wrapper already sets "kind"
            if ("kind".equals(name)) continue;

            // We never copy 'isXxx' booleans; those should have been normalized earlier.
            if (name.startsWith("is") && name.length() > 2 && Character.isUpperCase(name.charAt(2))) continue;

            // Avoid copying the old element name
            if ("name".equals(name) && "element".equals(legacy.getTagName())) continue;

            dst.setAttribute(name, value);
        }
    }

    // ----- staticText -----

    private static void convertStaticTextContents(Element legacy, Element dst, Document doc)
    {
        // textElement -> fontSize/bold/italic/underline/strikeThrough + hTextAlign/vTextAlign
        Element textElement = firstChildElementByTag(legacy, "textElement");
        if (textElement != null)
        {
            // alignments
            if (textElement.hasAttribute("textAlignment"))
            {
                dst.setAttribute("hTextAlign", textElement.getAttribute("textAlignment"));
            }
            if (textElement.hasAttribute("verticalAlignment"))
            {
                dst.setAttribute("vTextAlign", textElement.getAttribute("verticalAlignment"));
            }

            Element font = firstChildElementByTag(textElement, "font");
            if (font != null)
            {
                if (font.hasAttribute("size")) dst.setAttribute("fontSize", font.getAttribute("size"));
                if (font.hasAttribute("name")) dst.setAttribute("fontName", font.getAttribute("name"));

                if ("true".equalsIgnoreCase(font.getAttribute("isBold"))) dst.setAttribute("bold", "true");
                if ("true".equalsIgnoreCase(font.getAttribute("isItalic"))) dst.setAttribute("italic", "true");
                if ("true".equalsIgnoreCase(font.getAttribute("isUnderline"))) dst.setAttribute("underline", "true");
                if ("true".equalsIgnoreCase(font.getAttribute("isStrikeThrough"))) dst.setAttribute("strikeThrough", "true");
            }
        }

        Element text = firstChildElementByTag(legacy, "text");
        if (text != null)
        {
            String t = extractTrimmedText(text);
            Element newText = doc.createElement("text");
            newText.appendChild(doc.createCDATASection(t));
            dst.appendChild(newText);
        }
    }

    // ----- textField -----

    private static void convertTextFieldContents(Element legacy, Element dst, Document doc)
    {
        // pattern attribute stays as-is (already on legacy textField)
        // textElement formatting
        Element textElement = firstChildElementByTag(legacy, "textElement");
        if (textElement != null)
        {
            if (textElement.hasAttribute("textAlignment"))
            {
                dst.setAttribute("hTextAlign", textElement.getAttribute("textAlignment"));
            }
            if (textElement.hasAttribute("verticalAlignment"))
            {
                dst.setAttribute("vTextAlign", textElement.getAttribute("verticalAlignment"));
            }

            Element font = firstChildElementByTag(textElement, "font");
            if (font != null)
            {
                if (font.hasAttribute("size")) dst.setAttribute("fontSize", font.getAttribute("size"));
                if (font.hasAttribute("name")) dst.setAttribute("fontName", font.getAttribute("name"));

                if ("true".equalsIgnoreCase(font.getAttribute("isBold"))) dst.setAttribute("bold", "true");
                if ("true".equalsIgnoreCase(font.getAttribute("isItalic"))) dst.setAttribute("italic", "true");
                if ("true".equalsIgnoreCase(font.getAttribute("isUnderline"))) dst.setAttribute("underline", "true");
                if ("true".equalsIgnoreCase(font.getAttribute("isStrikeThrough"))) dst.setAttribute("strikeThrough", "true");
            }
        }

        // Convert textFieldExpression -> expression (single CDATA, trimmed).
        Element tfe = firstChildElementByTag(legacy, "textFieldExpression");
        if (tfe != null)
        {
            String exprText = extractTrimmedText(tfe);
            Element expr = createExpressionElement(doc, exprText);
            dst.appendChild(expr);
        }

        // Also normalize any other legacy expression containers we might have moved/copied
        // (e.g. printWhenExpression if it appears as element in old forms).
    }

    // ----- image -----

    private static void convertImageContents(Element legacy, Element dst, Document doc)
    {
        Element imageExpr = firstChildElementByTag(legacy, "imageExpression");
        if (imageExpr != null)
        {
            String exprText = extractTrimmedText(imageExpr);
            Element expr = createExpressionElement(doc, exprText);
            dst.appendChild(expr);
        }

        // If there are hyperlink expressions etc, convert/move as best-effort
        moveConvertedChildren(legacy, dst);

        // usingCache was normalized earlier if present
    }

    // ----- subreport -----

    private static void convertSubreportContents(Element legacy, Element dst, Document doc)
    {
        Element srExpr = firstChildElementByTag(legacy, "subreportExpression");
        if (srExpr != null)
        {
            String exprText = extractTrimmedText(srExpr);
            Element expr = createExpressionElement(doc, exprText);
            dst.appendChild(expr);
        }

        // parametersMapExpression / connectionExpression / dataSourceExpression are themselves expressions in legacy JRXML.
        for (String exprTag : new String[]{"parametersMapExpression", "connectionExpression", "dataSourceExpression"})
        {
            Element e = firstChildElementByTag(legacy, exprTag);
            if (e != null)
            {
                Element copied = (Element) e.cloneNode(true);
                normalizeExpressionContainer(copied, doc);
                dst.appendChild(copied);
            }
        }

        // subreportParameter -> parameter (and normalize its expression child)
        NodeList params = legacy.getElementsByTagName("subreportParameter");
        List<Element> pl = new ArrayList<>();
        for (int i = 0; i < params.getLength(); i++) pl.add((Element) params.item(i));
        for (Element p : pl)
        {
            Element newP = doc.createElement("parameter");
            if (p.hasAttribute("name")) newP.setAttribute("name", p.getAttribute("name"));

            Element spe = firstChildElementByTag(p, "subreportParameterExpression");
            if (spe != null)
            {
                String exprText = extractTrimmedText(spe);
                Element expr = createExpressionElement(doc, exprText);
                newP.appendChild(expr);
            }
            dst.appendChild(newP);
        }

        // returnValue stays as-is; just move it.
        NodeList rvs = legacy.getElementsByTagName("returnValue");
        List<Element> rvl = new ArrayList<>();
        for (int i = 0; i < rvs.getLength(); i++) rvl.add((Element) rvs.item(i));
        for (Element rv : rvl)
        {
            dst.appendChild((Element) rv.cloneNode(true));
        }
    }

    private static void moveConvertedChildren(Element legacy, Element dst)
    {
        // Move any children that are already in element-kind form or expression containers we didn't explicitly handle.
        List<Node> kids = childNodesSnapshot(legacy);
        for (Node k : kids)
        {
            if (k.getNodeType() != Node.ELEMENT_NODE) continue;
            Element ke = (Element) k;
            String tag = ke.getTagName();
            if ("reportElement".equals(tag) || "textElement".equals(tag) || "textFieldExpression".equals(tag) || "imageExpression".equals(tag) ||
                "subreportExpression".equals(tag) || "subreportParameter".equals(tag))
            {
                continue;
            }
            // Best effort: clone and append
            dst.appendChild((Element) ke.cloneNode(true));
        }
    }

    // ---------------- Expression helpers ----------------

    private static Element createExpressionElement(Document doc, String text)
    {
        Element e = doc.createElement("expression");
        // Keep exactly one CDATA child and nothing else.
        e.appendChild(doc.createCDATASection(text));
        return e;
    }

    private static String extractTrimmedText(Element exprContainer)
    {
        String s = exprContainer.getTextContent();
        if (s == null) return "";
        // Trim only the ends, preserve internal newlines.
        return trimEndsPreserveInternal(s);
    }

    private static String trimEndsPreserveInternal(String s)
    {
        int start = 0;
        int end = s.length();
        while (start < end && Character.isWhitespace(s.charAt(start))) start++;
        while (end > start && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    /**
     * For tags that are themselves "expression containers" in JRXML (e.g. <initialValueExpression>),
     * normalize their content to a single CDATA child with trimmed text.
     */
    private static void normalizeExpressionContainer(Element e, Document doc)
    {
        String text = extractTrimmedText(e);
        // Remove all children
        while (e.getFirstChild() != null) e.removeChild(e.getFirstChild());
        e.appendChild(doc.createCDATASection(text));
        // Remove legacy attributes like class on expressions; JR7 element-kind examples don't use it.
        if (e.hasAttribute("class")) e.removeAttribute("class");
    }

    // ---------------- DOM utilities ----------------

    private static Element firstChildElementByTag(Element parent, String tag)
    {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++)
        {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(((Element) n).getTagName()))
            {
                return (Element) n;
            }
        }
        return null;
    }

    // ---------------- Validation ----------------

    private static final class ValidationReport
    {
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    private static final class JrxmlValidator
    {
        private final CorpusModel corpus;
        private final Log log;

        // Elements/tags that must NOT appear after conversion.
        private static final Set<String> LEGACY_FORBIDDEN_TAGS = Set.of(
                "textFieldExpression", "groupExpression", "variableExpression",
                "subreportExpression", "subreportParameterExpression", "imageExpression"
        );

        // Attributes that must NOT appear after conversion.
        private static final Set<String> LEGACY_FORBIDDEN_ATTRS = Set.of(
                "isBlankWhenNull", "isUsingCache", "isSplitAllowed", "isDefault", "isPrintRepeatedValues"
        );

        // Some tags have well-known "band-only" children in JasperReports.
        private static final Set<String> BAND_CONTAINERS = Set.of(
                "title", "pageHeader", "pageFooter", "columnHeader", "columnFooter",
                "detail", "summary", "background", "noData", "lastPageFooter",
                "groupHeader", "groupFooter"
        );

        JrxmlValidator(CorpusModel corpus, Log log)
        {
            this.corpus = corpus;
            this.log = log;
        }

        ValidationReport validate(Document doc)
        {
            ValidationReport r = new ValidationReport();
            validateElement(doc.getDocumentElement(), "/", r);
            return r;
        }

        private void validateElement(Element e, String path, ValidationReport r)
        {
            String tag = e.getTagName();
            String here = path + tag;

            if (LEGACY_FORBIDDEN_TAGS.contains(tag))
            {
                r.errors.add(here + ": legacy tag should not exist in v7 output");
            }

            // Attributes
            NamedNodeMap attrs = e.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++)
            {
                Node a = attrs.item(i);
                String an = a.getNodeName();
                if (LEGACY_FORBIDDEN_ATTRS.contains(an))
                {
                    r.errors.add(here + ": legacy attribute should not exist in v7 output: " + an);
                }
            }

            // Structural checks from corpus (best effort)
            CorpusTagSpec spec = corpus.tags.get(tag);
            Set<String> allowedChildren = new HashSet<>();
            if (spec != null)
            {
                allowedChildren.addAll(spec.childElements);
            }

            // Hard-coded supplementation: common containers allow <band>
            if (BAND_CONTAINERS.contains(tag))
            {
                allowedChildren.add("band");
            }

            
            // Additional allowances for <element kind="..."> nodes where children depend on the kind.
            if ("element".equals(tag) && e.hasAttribute("kind"))
            {
                String k = e.getAttribute("kind");
                switch (k)
                {
                    case "textField":
                        allowedChildren.add("expression");
                        break;
                    case "staticText":
                        allowedChildren.add("text");
                        break;
                    case "image":
                        allowedChildren.add("expression");
                        break;
                    case "subreport":
                        allowedChildren.add("expression");
                        allowedChildren.add("parameter");
                        allowedChildren.add("parametersMapExpression");
                        allowedChildren.add("connectionExpression");
                        allowedChildren.add("dataSourceExpression");
                        allowedChildren.add("returnValue");
                        break;
                    case "frame":
                        allowedChildren.add("element");
                        break;
                    default:
                        break;
                }
            }

// Validate children
            NodeList kids = e.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++)
            {
                Node n = kids.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element c = (Element) n;
                String ctag = c.getTagName();

                if (spec != null && !allowedChildren.isEmpty() && !allowedChildren.contains(ctag))
                {
                    r.warnings.add(here + ": unexpected child <" + ctag + ">");
                }

                // Recurse
                validateElement(c, here + "/", r);
            }

            // Validate attributes against corpus (best effort)
            if (spec != null && !spec.attributes.isEmpty())
            {
                Set<String> allowedAttrs = new HashSet<>(spec.attributes);

                // Additional per-kind attribute allowances for <element kind="...">.
                if ("element".equals(tag) && e.hasAttribute("kind"))
                {
                    String k = e.getAttribute("kind");
                    switch (k)
                    {
                        case "textField":
                            allowedAttrs.addAll(Arrays.asList(
                                    "blankWhenNull", "pattern",
                                    "fontSize", "fontName",
                                    "bold", "italic", "underline", "strikeThrough",
                                    "hTextAlign", "vTextAlign",
                                    "printWhenDetailOverflows", "stretchType", "positionType",
                                    "printRepeatedValues", "removeLineWhenBlank"
                            ));
                            break;

                        case "staticText":
                            allowedAttrs.addAll(Arrays.asList(
                                    "fontSize", "fontName",
                                    "bold", "italic", "underline", "strikeThrough",
                                    "hTextAlign", "vTextAlign",
                                    "printWhenDetailOverflows", "stretchType", "positionType",
                                    "printRepeatedValues", "removeLineWhenBlank"
                            ));
                            break;

                        case "image":
                            allowedAttrs.addAll(Arrays.asList(
                                    "usingCache", "hImageAlign", "vImageAlign"
                            ));
                            break;

                        case "subreport":
                            allowedAttrs.addAll(Arrays.asList(
                                    "usingCache"
                            ));
                            break;

                        default:
                            break;
                    }
                }

                for (int i = 0; i < attrs.getLength(); i++)
                {
                    String an = attrs.item(i).getNodeName();
                    if (an.startsWith("xmlns") || an.startsWith("xsi:")) continue;
                    if (!allowedAttrs.contains(an))
                    {
                        r.warnings.add(here + ": unexpected attribute '" + an + "'");
                    }
                }
            }
        }
    }

    private static final class CorpusModel
    {
        final Path sourcePath;
        final Map<String, CorpusTagSpec> tags;

        private CorpusModel(Path sourcePath, Map<String, CorpusTagSpec> tags)
        {
            this.sourcePath = sourcePath;
            this.tags = tags;
        }

        static CorpusModel load(Path jsonPath, Log log) throws IOException
        {
            if (!Files.exists(jsonPath))
            {
                throw new FileNotFoundException("Corpus model file not found: " + jsonPath);
            }

            ObjectMapper om = new ObjectMapper();
            Map<?, ?> root = om.readValue(Files.newInputStream(jsonPath), Map.class);

            Object tagsObj = root.get("tags");
            if (!(tagsObj instanceof Map))
            {
                throw new IOException("Invalid corpus JSON: missing 'tags' map");
            }

            Map<String, CorpusTagSpec> out = new HashMap<>();
            Map<?, ?> tags = (Map<?, ?>) tagsObj;
            for (Map.Entry<?, ?> ent : tags.entrySet())
            {
                String tagName = String.valueOf(ent.getKey());
                Object val = ent.getValue();
                if (!(val instanceof Map)) continue;

                Map<?, ?> specMap = (Map<?, ?>) val;

                Set<String> attrs = readStringSet(specMap.get("attributes"));
                Set<String> kids = readStringSet(specMap.get("child_elements"));

                out.put(tagName, new CorpusTagSpec(attrs, kids));
            }

            return new CorpusModel(jsonPath, out);
        }

        @SuppressWarnings("unchecked")
        private static Set<String> readStringSet(Object o)
        {
            if (!(o instanceof List)) return Collections.emptySet();
            List<?> l = (List<?>) o;
            Set<String> s = new HashSet<>();
            for (Object x : l)
            {
                if (x == null) continue;
                s.add(String.valueOf(x));
            }
            return s;
        }
    }

    private static final class CorpusTagSpec
    {
        final Set<String> attributes;
        final Set<String> childElements;

        CorpusTagSpec(Set<String> attributes, Set<String> childElements)
        {
            this.attributes = attributes != null ? attributes : Collections.emptySet();
            this.childElements = childElements != null ? childElements : Collections.emptySet();
        }
    }

    // ---------------- Logging ----------------

    private static final class Log implements Closeable
    {
        private final PrintStream out;
        private final PrintStream err;
        private final PrintStream file;
        private final boolean debug;

        static Log open(Path logPath, boolean debug) throws IOException
        {
            PrintStream f = null;
            if (logPath != null)
            {
                Files.createDirectories(logPath.toAbsolutePath().getParent());
                f = new PrintStream(Files.newOutputStream(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
            }
            return new Log(System.out, System.err, f, debug);
        }

        Log(PrintStream out, PrintStream err, PrintStream file, boolean debug)
        {
            this.out = out;
            this.err = err;
            this.file = file;
            this.debug = debug;
        }

        PrintStream err() { return err; }

        void info(String msg)
        {
            println(out, "INFO ", msg);
        }

        void warn(String msg)
        {
            println(err, "WARN ", msg);
        }

        void error(String msg)
        {
            println(err, "ERROR", msg);
        }

        void debug(String msg)
        {
            if (!debug) return;
            println(err, "DEBUG", msg);
        }

        private void println(PrintStream ps, String level, String msg)
        {
            String line = level + " " + msg;
            ps.println(line);
            if (file != null) file.println(line);
        }

        @Override
        public void close()
        {
            if (file != null) file.close();
        }
    }
}
