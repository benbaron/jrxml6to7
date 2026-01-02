
package jrxml6to7;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.*;
import org.xml.sax.*;

public class Jrxml6To7Converter_patched6
{
	
	private static boolean DEBUG = false;
	
	public static void main(String[] args) throws Exception
	{
		
		if (args.length < 2)
		{
			System.err
				.println("Usage: jrxml6to7 <inputDir> <outputDir> [--debug]");
			System.exit(1);
		}
		
		Path in = Paths.get(args[0]);
		Path out = Paths.get(args[1]);
		
		if (args.length > 2 && "--debug".equals(args[2]))
		{
			DEBUG = true;
		}
		
		Files.createDirectories(out);
		
		Files.walkFileTree(in, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file,
				BasicFileAttributes attrs) throws IOException
			{
				Path rel = in.relativize(file);
				Path dest = out.resolve(rel);
				Files.createDirectories(dest.getParent());
				
				if (file.toString().endsWith(".jrxml"))
				{
					
					try (InputStream is = Files.newInputStream(file);
						OutputStream os = Files.newOutputStream(dest))
					{
						convertSingle(is, os, file.toString());
					}
					catch (Exception e)
					{
						System.err.println("FAILED: " + file);
						e.printStackTrace();
					}
					
				}
				else
				{
					Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
				}
				
				return FileVisitResult.CONTINUE;
				
			}
			
		});
		
	}
	
	// ---------------------------------------------------------------------
	
	private static void convertSingle(InputStream in, OutputStream out,
		String name)
		throws Exception
	{
		
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(false);
		dbf.setValidating(false);
		
		DocumentBuilder db = dbf.newDocumentBuilder();
		db.setEntityResolver(
			(pid, sid) -> sid != null && sid.contains("jasperreport.dtd") ?
				new InputSource(
					new StringReader("<!ELEMENT jasperReport ANY>")) :
				null
		);
		
		Document doc = db.parse(in);
		
		removeDoctype(doc);
		stripNamespaces(doc.getDocumentElement());
		
		convertVariables(doc);
		convertGroups(doc);
		convertElements(doc.getDocumentElement());
		
		flattenSectionBands(doc,
			"title", "pageHeader", "columnHeader", "columnFooter",
			"pageFooter", "lastPageFooter", "summary", "background", "noData");
		
		write(doc, out);
		
	}
	
	// ---------------------------------------------------------------------
	// VARIABLES
	
	private static void convertVariables(Document doc)
	{
		NodeList vars = doc.getElementsByTagName("variable");
		
		for (int i = 0; i < vars.getLength(); i++)
		{
			Element v = (Element) vars.item(i);
			Element ve = firstChild(v, "variableExpression");
			
			if (ve != null)
			{
				Element expr = doc.createElement("expression");
				expr.appendChild(doc.createCDATASection(textOf(ve)));
				v.replaceChild(expr, ve);
			}
			
		}
		
	}
	
	// ---------------------------------------------------------------------
	// GROUP EXPRESSIONS
	
	private static void convertGroups(Document doc)
	{
		NodeList groups = doc.getElementsByTagName("group");
		
		for (int i = 0; i < groups.getLength(); i++)
		{
			Element g = (Element) groups.item(i);
			Element ge = firstChild(g, "groupExpression");
			
			if (ge != null)
			{
				Element expr = doc.createElement("expression");
				expr.appendChild(doc.createCDATASection(textOf(ge)));
				g.replaceChild(expr, ge);
			}
			
		}
		
	}
	
	// ---------------------------------------------------------------------
	// ELEMENT CONVERSION
	
	private static void convertElements(Element parent)
	{
		Node n = parent.getFirstChild();
		
		while (n != null)
		{
			Node next = n.getNextSibling();
			
			if (n instanceof Element e)
			{
				
				switch(e.getTagName())
				{
					case "textField" -> convertTextField(e);
					case "staticText" -> convertStaticText(e);
					case "line", "rectangle", "ellipse" -> convertGraphic(e);
					case "subreport" -> convertSubreport(e);
					default -> convertElements(e);
				}
				
			}
			
			n = next;
		}
		
	}
	
	// ---------------------------------------------------------------------
	// TEXTFIELD
	
	private static void convertTextField(Element tf)
	{
		Document doc = tf.getOwnerDocument();
		Element el = doc.createElement("element");
		el.setAttribute("kind", "textField");
		
		moveReportElement(tf, el);
		moveTextFormatting(tf, el);
		
		Element tfe = firstChild(tf, "textFieldExpression");
		
		if (tfe != null)
		{
			Element expr = doc.createElement("expression");
			expr.appendChild(doc.createCDATASection(textOf(tfe)));
			el.appendChild(expr);
		}
		
		moveRemaining(tf, el, tfe);
		tf.getParentNode().replaceChild(el, tf);
		
	}
	
	// ---------------------------------------------------------------------
	// STATIC TEXT
	
	private static void convertStaticText(Element st)
	{
		Document doc = st.getOwnerDocument();
		Element el = doc.createElement("element");
		el.setAttribute("kind", "staticText");
		
		moveReportElement(st, el);
		moveTextFormatting(st, el);
		
		String text = textOf(st);
		
		if (!text.isEmpty())
		{
			Element t = doc.createElement("text");
			t.appendChild(doc.createCDATASection(text));
			el.appendChild(t);
		}
		
		st.getParentNode().replaceChild(el, st);
		
	}
	
	// ---------------------------------------------------------------------
	// GRAPHICS
	
	private static void convertGraphic(Element g)
	{
		Document doc = g.getOwnerDocument();
		Element el = doc.createElement("element");
		el.setAttribute("kind", g.getTagName());
		
		moveReportElement(g, el);
		g.getParentNode().replaceChild(el, g);
		
	}
	
	// ---------------------------------------------------------------------
	// SUBREPORT (CRITICAL FIX)
	
	private static void convertSubreport(Element legacy)
	{
		Document doc = legacy.getOwnerDocument();
		Element el = doc.createElement("element");
		el.setAttribute("kind", "subreport");
		
		moveReportElement(legacy, el);
		
		Element ds = firstChild(legacy, "dataSourceExpression");
		NodeList params = legacy.getElementsByTagName("subreportParameter");
		
		boolean useMap =
			ds != null && params.getLength() > 0;
		
		if (useMap)
		{
			StringBuilder map = new StringBuilder();
			map.append("new java.util.HashMap() {{\n");
			
			for (int i = 0; i < params.getLength(); i++)
			{
				Element p = (Element) params.item(i);
				String name = p.getAttribute("name");
				Element pe = firstChild(p, "subreportParameterExpression");
				map.append("put(\"").append(name).append("\", ")
					.append(textOf(pe)).append(");\n");
			}
			
			map.append("}}");
			
			Element pme = doc.createElement("parametersMapExpression");
			pme.appendChild(doc.createCDATASection(map.toString()));
			el.appendChild(pme);
			
			if (DEBUG)
				System.out.println("Subreport: using parametersMapExpression");
		}
		else
		{
			
			for (int i = 0; i < params.getLength(); i++)
			{
				Element p = (Element) params.item(i);
				Element np = doc.createElement("parameter");
				np.setAttribute("name", p.getAttribute("name"));
				
				Element pe = firstChild(p, "subreportParameterExpression");
				
				if (pe != null)
				{
					Element spe =
						doc.createElement("subreportParameterExpression");
					spe.appendChild(doc.createCDATASection(textOf(pe)));
					np.appendChild(spe);
				}
				
				el.appendChild(np);
			}
			
		}
		
		moveExpression(legacy, "subreportExpression", el);
		moveExpression(legacy, "dataSourceExpression", el);
		
		legacy.getParentNode().replaceChild(el, legacy);
		
	}
	
	// ---------------------------------------------------------------------
	// BAND FLATTENING
	
	private static void flattenSectionBands(Document doc, String... sections)
	{
		
		for (String s : sections)
		{
			NodeList nl = doc.getElementsByTagName(s);
			
			for (int i = 0; i < nl.getLength(); i++)
			{
				Element sec = (Element) nl.item(i);
				Element band = firstChild(sec, "band");
				
				if (band != null)
				{
					sec.setAttribute("height", band.getAttribute("height"));
					moveAll(band, sec);
					sec.removeChild(band);
				}
				
			}
			
		}
		
	}
	
	// ---------------------------------------------------------------------
	// HELPERS
	
	private static void moveReportElement(Element from, Element to)
	{
		Element re = firstChild(from, "reportElement");
		
		if (re != null)
		{
			copyAttrs(re, to);
		}
		
	}
	
	private static void moveTextFormatting(Element from, Element to)
	{
		Element te = firstChild(from, "textElement");
		
		if (te != null)
		{
			Element font = firstChild(te, "font");
			
			if (font != null)
			{
				if (font.hasAttribute("size"))
					to.setAttribute("fontSize", font.getAttribute("size"));
				if ("true".equals(font.getAttribute("isBold")))
					to.setAttribute("bold", "true");
			}
			
		}
		
	}
	
	private static void moveExpression(Element from, String tag, Element to)
	{
		Element e = firstChild(from, tag);
		
		if (e != null)
		{
			Element ne = to.getOwnerDocument().createElement(tag);
			ne.appendChild(to.getOwnerDocument().createCDATASection(textOf(e)));
			to.appendChild(ne);
		}
		
	}
	
	private static Element firstChild(Element p, String name)
	{
		
		for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling())
		{
			if (n instanceof Element e && e.getTagName().equals(name))
				return e;
		}
		
		return null;
		
	}
	
	private static String textOf(Node n)
	{
		return n.getTextContent().trim();
		
	}
	
	private static void moveAll(Element from, Element to)
	{
		while (from.getFirstChild() != null)
			to.appendChild(from.getFirstChild());
			
	}
	
	private static void moveRemaining(Element from, Element to, Node... used)
	{
		Set<Node> skip = new HashSet<>(Arrays.asList(used));
		Node n = from.getFirstChild();
		
		while (n != null)
		{
			Node next = n.getNextSibling();
			
			if (!skip.contains(n))
			{
				to.appendChild(n);
			}
			
			n = next;
		}
		
	}
	
	private static void copyAttrs(Element from, Element to)
	{
		NamedNodeMap m = from.getAttributes();
		
		for (int i = 0; i < m.getLength(); i++)
		{
			Attr a = (Attr) m.item(i);
			to.setAttribute(a.getName(), a.getValue());
		}
		
	}
	
	private static void removeDoctype(Document doc)
	{
		if (doc.getDoctype() != null)
			doc.removeChild(doc.getDoctype());
			
	}
	
	private static void stripNamespaces(Element root)
	{
		root.removeAttribute("xmlns");
		NamedNodeMap m = root.getAttributes();
		
		for (int i = m.getLength() - 1; i >= 0; i--)
		{
			String n = m.item(i).getNodeName();
			if (n.startsWith("xmlns") || n.startsWith("xsi"))
				root.removeAttribute(n);
		}
		
	}
	
	private static void write(Document doc, OutputStream out) throws Exception
	{
		Transformer t = TransformerFactory.newInstance().newTransformer();
		t.setOutputProperty(OutputKeys.INDENT, "yes");
		t.transform(new DOMSource(doc), new StreamResult(out));
		
	}
	
}
