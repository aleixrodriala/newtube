package com.newtube.mobile.casting.proxy;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal XML tree parser/serializer for {@link MpdRewriter}.
 *
 * <p>Why not XmlPullParser: the only implementations reachable here are framework-backed
 * ({@code android.util.Xml} / the android.jar {@code XmlPullParserFactory} stub throws in pure-JVM
 * unit tests) and kxml2 is NOT on the test classpath (checked; adding deps is off-limits). This is
 * a real single-pass tokenizer over well-formed XML - element stack, quoted attributes, entity
 * handling, comment/CDATA passthrough - not a regex over the document.</p>
 *
 * <p>Fidelity: attribute order, raw (still-escaped) attribute values, text nodes including
 * inter-tag indentation, the XML prolog, and self-closing form are all captured verbatim and
 * re-emitted, so the round trip is byte-faithful except for attribute quote normalization
 * (single quotes become double quotes; the upstream serializer already emits double quotes).
 * Scoped to what {@code YouTubeMPDBuilder} output needs: no DTD internals, no processing
 * instructions inside the root.</p>
 */
final class MpdXml {

    /** Element node. Children are either {@link Element} or {@link String} raw-text/comment runs. */
    static final class Element {
        final String name;
        /** Attribute pairs {name, rawValue} in document order; rawValue keeps original escaping. */
        final List<String[]> attributes = new ArrayList<>();
        final List<Object> children = new ArrayList<>();
        boolean selfClosing;

        Element(String name) {
            this.name = name;
        }

        @Nullable
        String rawAttr(String attrName) {
            for (String[] pair : attributes) {
                if (pair[0].equals(attrName)) {
                    return pair[1];
                }
            }
            return null;
        }

        /** Attribute value with XML entities resolved (BaseURLs are full of {@code &amp;}). */
        @Nullable
        String attr(String attrName) {
            String raw = rawAttr(attrName);
            return raw != null ? unescape(raw) : null;
        }

        /** Replaces the attribute's value; the new value is escaped on write. */
        void setAttr(String attrName, String value) {
            String raw = escape(value);
            for (String[] pair : attributes) {
                if (pair[0].equals(attrName)) {
                    pair[1] = raw;
                    return;
                }
            }
            attributes.add(new String[]{attrName, raw});
        }

        List<Element> childElements(String childName) {
            List<Element> result = new ArrayList<>();
            for (Object child : children) {
                if (child instanceof Element && ((Element) child).name.equals(childName)) {
                    result.add((Element) child);
                }
            }
            return result;
        }

        @Nullable
        Element firstChild(String childName) {
            for (Object child : children) {
                if (child instanceof Element && ((Element) child).name.equals(childName)) {
                    return (Element) child;
                }
            }
            return null;
        }

        /** Concatenated unescaped text content (comments excluded). */
        String text() {
            StringBuilder sb = new StringBuilder();
            for (Object child : children) {
                if (child instanceof String && !((String) child).startsWith("<!--")) {
                    sb.append(unescape((String) child));
                }
            }
            return sb.toString();
        }

        /** Replaces ALL children with a single text node (used on leaf tags like BaseURL). */
        void setText(String value) {
            children.clear();
            children.add(escape(value));
            selfClosing = false;
        }
    }

    static final class Document {
        /** Everything before the root element's {@code <} - prolog, comments, whitespace - verbatim. */
        String preRootRaw = "";
        Element root;
    }

    private MpdXml() {
    }

    static Document parse(String xml) throws IOException {
        return new Parser(xml).parseDocument();
    }

    static String serialize(Document doc) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(doc.preRootRaw);
        write(sb, doc.root);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Element el) {
        sb.append('<').append(el.name);
        for (String[] pair : el.attributes) {
            sb.append(' ').append(pair[0]).append("=\"").append(pair[1].replace("\"", "&quot;")).append('"');
        }
        if (el.selfClosing && el.children.isEmpty()) {
            sb.append(" />");
            return;
        }
        sb.append('>');
        for (Object child : el.children) {
            if (child instanceof Element) {
                write(sb, (Element) child);
            } else {
                sb.append((String) child);
            }
        }
        sb.append("</").append(el.name).append('>');
    }

    // ---------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String xml) {
            s = xml;
        }

        Document parseDocument() throws IOException {
            Document doc = new Document();
            int rootStart = findRootStart();
            doc.preRootRaw = s.substring(0, rootStart);
            i = rootStart;
            doc.root = parseElement();
            return doc;
        }

        /** Skip prolog/comments/DOCTYPE; return the index of the root element's '<'. */
        private int findRootStart() throws IOException {
            int pos = 0;
            while (pos < s.length()) {
                int lt = s.indexOf('<', pos);
                if (lt < 0) {
                    break;
                }
                if (s.startsWith("<?", lt)) {
                    pos = advancePast(lt, "?>");
                } else if (s.startsWith("<!--", lt)) {
                    pos = advancePast(lt, "-->");
                } else if (s.startsWith("<!", lt)) {
                    pos = advancePast(lt, ">");
                } else {
                    return lt;
                }
            }
            throw new IOException("XML: no root element");
        }

        private int advancePast(int from, String closer) throws IOException {
            int end = s.indexOf(closer, from);
            if (end < 0) {
                throw new IOException("XML: unterminated construct at " + from);
            }
            return end + closer.length();
        }

        private Element parseElement() throws IOException {
            expect('<');
            Element el = new Element(readName());
            // Attributes until '>' or '/>'.
            while (true) {
                skipWs();
                char c = peek();
                if (c == '/') {
                    i++;
                    expect('>');
                    el.selfClosing = true;
                    return el;
                }
                if (c == '>') {
                    i++;
                    break;
                }
                String attrName = readName();
                skipWs();
                expect('=');
                skipWs();
                char quote = peek();
                if (quote != '"' && quote != '\'') {
                    throw new IOException("XML: unquoted attribute at " + i);
                }
                i++;
                int end = s.indexOf(quote, i);
                if (end < 0) {
                    throw new IOException("XML: unterminated attribute value at " + i);
                }
                el.attributes.add(new String[]{attrName, s.substring(i, end)});
                i = end + 1;
            }
            // Content until the matching end tag.
            while (true) {
                int lt = s.indexOf('<', i);
                if (lt < 0) {
                    throw new IOException("XML: unexpected EOF inside <" + el.name + ">");
                }
                if (lt > i) {
                    el.children.add(s.substring(i, lt));
                    i = lt;
                }
                if (s.startsWith("</", i)) {
                    i += 2;
                    String endName = readName();
                    skipWs();
                    expect('>');
                    if (!endName.equals(el.name)) {
                        throw new IOException("XML: mismatched </" + endName + ">, expected </" + el.name + ">");
                    }
                    return el;
                } else if (s.startsWith("<!--", i)) {
                    int end = advancePast(i, "-->");
                    el.children.add(s.substring(i, end));
                    i = end;
                } else if (s.startsWith("<![CDATA[", i)) {
                    int end = advancePast(i, "]]>");
                    el.children.add(s.substring(i, end));
                    i = end;
                } else {
                    el.children.add(parseElement());
                }
            }
        }

        private String readName() throws IOException {
            int start = i;
            while (i < s.length() && isNameChar(s.charAt(i))) {
                i++;
            }
            if (i == start) {
                throw new IOException("XML: expected name at " + i);
            }
            return s.substring(start, i);
        }

        private static boolean isNameChar(char c) {
            return Character.isLetterOrDigit(c) || c == ':' || c == '_' || c == '-' || c == '.';
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private char peek() throws IOException {
            if (i >= s.length()) {
                throw new IOException("XML: unexpected EOF");
            }
            return s.charAt(i);
        }

        private void expect(char c) throws IOException {
            if (peek() != c) {
                throw new IOException("XML: expected '" + c + "' at " + i);
            }
            i++;
        }
    }

    // ---------------------------------------------------------------------------------
    // Entities
    // ---------------------------------------------------------------------------------

    /** Resolve the predefined entities plus decimal/hex character references. */
    static String unescape(String raw) {
        int amp = raw.indexOf('&');
        if (amp < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int pos = 0;
        while (amp >= 0) {
            sb.append(raw, pos, amp);
            int semi = raw.indexOf(';', amp);
            String replacement = null;
            if (semi > amp) {
                String entity = raw.substring(amp + 1, semi);
                switch (entity) {
                    case "amp": replacement = "&"; break;
                    case "lt": replacement = "<"; break;
                    case "gt": replacement = ">"; break;
                    case "quot": replacement = "\""; break;
                    case "apos": replacement = "'"; break;
                    default:
                        if (entity.startsWith("#")) {
                            try {
                                int code = entity.startsWith("#x") || entity.startsWith("#X")
                                        ? Integer.parseInt(entity.substring(2), 16)
                                        : Integer.parseInt(entity.substring(1));
                                replacement = new String(Character.toChars(code));
                            } catch (Exception e) {
                                // fall through: keep literal
                            }
                        }
                        break;
                }
            }
            if (replacement != null) {
                sb.append(replacement);
                pos = semi + 1;
            } else {
                sb.append('&');
                pos = amp + 1;
            }
            amp = raw.indexOf('&', pos);
        }
        sb.append(raw, pos, raw.length());
        return sb.toString();
    }

    /** Escape a plain value for embedding as attribute value or text. */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
