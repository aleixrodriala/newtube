package com.newtube.mobile.player;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.formatbuilders.mpdbuilder.YouTubeMPDBuilder;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(open-cpu): the generated DASH manifest without the XML text in between.
 * <p>
 * Every open printed the whole MPD with KXmlSerializer (escaping every URL character by
 * character), encoded it to UTF-8, and then media3's DashManifestParser lexed it back with
 * KXmlParser - 50 KB for a plain video, ~160 KB with captions (one AdaptationSet per caption
 * translation, ~160 of them). That round trip was most of the {@code info -> prepare} gap.
 * <p>
 * Here {@link YouTubeMPDBuilder#writeTo} writes the very same document as serializer calls into
 * a {@link Recorder}, and a {@link Replay} hands them to the very same media3 parser as pull
 * events. Neither side is re-implemented: the equivalence rests only on the replay presenting what
 * KXmlParser would present for the serialized text - the element/attribute/text sequence minus
 * the serializer's indentation whitespace, which none of DashManifestParser's loops read. The
 * recorder refuses (see {@link Abort}) anything the text round trip could alter or reject:
 * characters XML cannot carry or would normalise (controls, tabs, line breaks, surrogates,
 * U+FFFE/F), duplicate or misplaced attributes, unbalanced tags, and serializer calls the
 * builder never makes. A refusal, like any failure of the replayed parse, sends the caller back
 * to the text route, which then behaves exactly as it always did.
 */
final class DirectMpd {
    private DirectMpd() {
    }

    /** Thrown by the recorder when the text route must be used instead. */
    static final class Abort extends RuntimeException {
        Abort(String reason) {
            super(reason, null, false, false);
        }
    }

    /**
     * The builder's calls for {@code formatInfo}, or null when the text route must build it:
     * another implementation (its createMpdStream need not be YouTubeMPDBuilder), live (the
     * caller hands those over as bytes), OTF (the builder fetches each init segment over the
     * network while writing - never run that twice on a fallback), explicit segment lists (rare;
     * left on the proven route), or a refusal.
     */
    @Nullable
    static Recorder record(MediaItemFormatInfo formatInfo) {
        if (!(formatInfo instanceof YouTubeMediaItemFormatInfo) || formatInfo.isLive()) {
            return null;
        }
        List<MediaFormat> formats = formatInfo.getAdaptiveFormats();
        if (formats != null) {
            for (MediaFormat format : formats) {
                if (format == null || format.isOtf() || format.getSegmentUrlList() != null
                        || format.getGlobalSegmentList() != null) {
                    return null;
                }
            }
        }
        Recorder recorder = new Recorder();
        try {
            return YouTubeMPDBuilder.writeTo(formatInfo, recorder) && recorder.isComplete()
                    ? recorder : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Serializer calls, kept as the event sequence a pull parser reports for their output. */
    static final class Recorder implements XmlSerializer {
        private final List<Event> mEvents = new ArrayList<>(1024);
        private final List<String> mOpen = new ArrayList<>();
        private Event mCurrentStart; // attributes are still allowed while this start is last
        private boolean mStarted;
        private boolean mEnded;

        boolean isComplete() {
            return mStarted && mEnded && mOpen.isEmpty() && !mEvents.isEmpty();
        }

        Replay replay() {
            return new Replay(mEvents);
        }

        @Override
        public void startDocument(String encoding, Boolean standalone) {
            if (mStarted) {
                throw new Abort("startDocument twice");
            }
            mStarted = true;
        }

        @Override
        public void endDocument() {
            if (!mOpen.isEmpty()) {
                throw new Abort("endDocument with open tags");
            }
            mEnded = true;
        }

        @Override
        public XmlSerializer startTag(String namespace, String name) {
            if (!mStarted || mEnded || (namespace != null && !namespace.isEmpty()) || !isName(name)
                    || (mOpen.isEmpty() && !mEvents.isEmpty())) {
                throw new Abort("startTag " + name);
            }
            Event start = new Event(XmlPullParser.START_TAG, name, null);
            mEvents.add(start);
            mOpen.add(name);
            mCurrentStart = start;
            return this;
        }

        @Override
        public XmlSerializer attribute(String namespace, String name, String value) {
            Event start = mCurrentStart;
            if (start == null || (namespace != null && !namespace.isEmpty()) || !isName(name)
                    || value == null || !isPlainText(value) || start.attributeValue(name) != null) {
                throw new Abort("attribute " + name);
            }
            start.addAttribute(name, value);
            return this;
        }

        @Override
        public XmlSerializer text(String text) {
            if (text == null) {
                throw new Abort("null text"); // the text route throws; let it
            }
            if (mOpen.isEmpty() || !isPlainText(text)) {
                throw new Abort("text");
            }
            mCurrentStart = null;
            if (text.isEmpty()) {
                return this; // nothing printed, no event
            }
            Event last = mEvents.get(mEvents.size() - 1);
            if (last.type == XmlPullParser.TEXT) {
                last.text = last.text + text; // adjacent text is one TEXT event
            } else {
                mEvents.add(new Event(XmlPullParser.TEXT, null, text));
            }
            return this;
        }

        @Override
        public XmlSerializer endTag(String namespace, String name) {
            if ((namespace != null && !namespace.isEmpty()) || mOpen.isEmpty()
                    || !mOpen.get(mOpen.size() - 1).equals(name)) {
                throw new Abort("endTag " + name);
            }
            mOpen.remove(mOpen.size() - 1);
            mEvents.add(new Event(XmlPullParser.END_TAG, name, null));
            mCurrentStart = null;
            return this;
        }

        @Override
        public void setFeature(String name, boolean state) {
            // Only indentation, which never reaches the parser's loops.
        }

        @Override
        public boolean getFeature(String name) {
            return false;
        }

        @Override
        public void setProperty(String name, Object value) {
            throw new Abort("setProperty");
        }

        @Override
        public Object getProperty(String name) {
            return null;
        }

        @Override
        public void setOutput(OutputStream os, String encoding) {
            throw new Abort("setOutput");
        }

        @Override
        public void setOutput(Writer writer) {
            throw new Abort("setOutput");
        }

        @Override
        public void setPrefix(String prefix, String namespace) {
            throw new Abort("setPrefix");
        }

        @Override
        public String getPrefix(String namespace, boolean generatePrefix) {
            throw new Abort("getPrefix");
        }

        @Override
        public int getDepth() {
            return mOpen.size();
        }

        @Override
        public String getNamespace() {
            return "";
        }

        @Override
        public String getName() {
            return mOpen.isEmpty() ? null : mOpen.get(mOpen.size() - 1);
        }

        @Override
        public XmlSerializer text(char[] buf, int start, int len) {
            return text(new String(buf, start, len));
        }

        @Override
        public void cdsect(String text) {
            throw new Abort("cdsect");
        }

        @Override
        public void entityRef(String text) {
            throw new Abort("entityRef");
        }

        @Override
        public void processingInstruction(String text) {
            throw new Abort("processingInstruction");
        }

        @Override
        public void comment(String text) {
            throw new Abort("comment");
        }

        @Override
        public void docdecl(String text) {
            throw new Abort("docdecl");
        }

        @Override
        public void ignorableWhitespace(String text) {
            throw new Abort("ignorableWhitespace");
        }

        @Override
        public void flush() {
        }

        private static boolean isName(String name) {
            if (name == null || name.isEmpty()) {
                return false;
            }
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
                if (!letter && (i == 0 || !((c >= '0' && c <= '9') || c == ':' || c == '_'
                        || c == '-' || c == '.'))) {
                    return false;
                }
            }
            return true;
        }

        /** Printed and parsed back unchanged: no controls (incl. tab/CR/LF), no surrogates. */
        private static boolean isPlainText(String text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 0x20 || (c >= 0xd800 && c <= 0xdfff) || c > 0xfffd) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Event {
        final int type;
        final String name;
        String text;
        private String[] mAttributes; // name, value pairs in call order
        private int mAttributeCount;

        Event(int type, String name, String text) {
            this.type = type;
            this.name = name;
            this.text = text;
        }

        void addAttribute(String attributeName, String value) {
            if (mAttributes == null) {
                mAttributes = new String[8];
            } else if (mAttributes.length == mAttributeCount * 2) {
                String[] grown = new String[mAttributes.length * 2];
                System.arraycopy(mAttributes, 0, grown, 0, mAttributes.length);
                mAttributes = grown;
            }
            mAttributes[mAttributeCount * 2] = attributeName;
            mAttributes[mAttributeCount * 2 + 1] = value;
            mAttributeCount++;
        }

        String attributeValue(String attributeName) {
            // Last match first, like KXmlParser (names are unique here anyway).
            for (int i = mAttributeCount - 1; i >= 0; i--) {
                if (mAttributes[i * 2].equals(attributeName)) {
                    return mAttributes[i * 2 + 1];
                }
            }
            return null;
        }
    }

    /**
     * A non-namespace-aware pull parser over recorded events, answering like KXmlParser does for
     * the same document (attribute namespace "", getText null on tags).
     */
    static final class Replay implements XmlPullParser {
        private final List<Event> mEvents;
        private int mIndex = -1;
        private int mDepth;

        Replay(List<Event> events) {
            mEvents = events;
        }

        private Event current() {
            return mIndex >= 0 && mIndex < mEvents.size() ? mEvents.get(mIndex) : null;
        }

        @Override
        public int getEventType() {
            if (mIndex < 0) {
                return START_DOCUMENT;
            }
            Event event = current();
            return event != null ? event.type : END_DOCUMENT;
        }

        @Override
        public int next() {
            Event previous = current();
            if (previous != null && previous.type == END_TAG) {
                mDepth--;
            }
            if (mIndex < mEvents.size()) {
                mIndex++;
            }
            Event event = current();
            if (event != null && event.type == START_TAG) {
                mDepth++;
            }
            return getEventType();
        }

        @Override
        public int nextToken() {
            return next();
        }

        @Override
        public String nextText() throws XmlPullParserException {
            if (getEventType() != START_TAG) {
                throw new XmlPullParserException("nextText: not on a start tag", this, null);
            }
            int type = next();
            String result = "";
            if (type == TEXT) {
                result = current().text;
                type = next();
            }
            if (type != END_TAG) {
                throw new XmlPullParserException("nextText: no end tag after text", this, null);
            }
            return result;
        }

        @Override
        public int nextTag() throws XmlPullParserException {
            int type = next();
            if (type == TEXT && isWhitespace()) {
                type = next();
            }
            if (type != START_TAG && type != END_TAG) {
                throw new XmlPullParserException("nextTag: expected a tag", this, null);
            }
            return type;
        }

        @Override
        public void require(int type, String namespace, String name) throws XmlPullParserException {
            if (type != getEventType() || (namespace != null && !namespace.equals(getNamespace()))
                    || (name != null && !name.equals(getName()))) {
                throw new XmlPullParserException("require failed", this, null);
            }
        }

        @Override
        public String getName() {
            Event event = current();
            return event != null && event.type != TEXT ? event.name : null;
        }

        @Override
        public String getText() {
            Event event = current();
            return event != null && event.type == TEXT ? event.text : null;
        }

        @Override
        public char[] getTextCharacters(int[] holderForStartAndLength) {
            String text = getText();
            if (text == null) {
                holderForStartAndLength[0] = -1;
                holderForStartAndLength[1] = -1;
                return null;
            }
            holderForStartAndLength[0] = 0;
            holderForStartAndLength[1] = text.length();
            return text.toCharArray();
        }

        @Override
        public boolean isWhitespace() throws XmlPullParserException {
            String text = getText();
            if (text == null) {
                throw new XmlPullParserException("isWhitespace: not text", this, null);
            }
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > ' ') {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isEmptyElementTag() throws XmlPullParserException {
            if (getEventType() != START_TAG) {
                throw new XmlPullParserException("isEmptyElementTag: not a start tag", this, null);
            }
            return mIndex + 1 < mEvents.size() && mEvents.get(mIndex + 1).type == END_TAG;
        }

        @Override
        public int getAttributeCount() {
            Event event = current();
            return event != null && event.type == START_TAG ? event.mAttributeCount : -1;
        }

        private Event startTag(int index) {
            Event event = current();
            if (event == null || event.type != START_TAG || index < 0 || index >= event.mAttributeCount) {
                throw new IndexOutOfBoundsException("attribute " + index);
            }
            return event;
        }

        @Override
        public String getAttributeName(int index) {
            return startTag(index).mAttributes[index * 2];
        }

        @Override
        public String getAttributeValue(int index) {
            return startTag(index).mAttributes[index * 2 + 1];
        }

        @Override
        public String getAttributeNamespace(int index) {
            startTag(index);
            return "";
        }

        @Override
        public String getAttributePrefix(int index) {
            startTag(index);
            return null;
        }

        @Override
        public String getAttributeType(int index) {
            startTag(index);
            return "CDATA";
        }

        @Override
        public boolean isAttributeDefault(int index) {
            startTag(index);
            return false;
        }

        @Override
        public String getAttributeValue(String namespace, String name) {
            Event event = current();
            if (event == null || event.type != START_TAG) {
                throw new IndexOutOfBoundsException("not a start tag");
            }
            if (namespace != null && !namespace.isEmpty()) {
                return null; // every attribute sits in the no-namespace ""
            }
            return event.attributeValue(name);
        }

        @Override
        public String getNamespace() {
            int type = getEventType();
            return type == START_TAG || type == END_TAG ? "" : null;
        }

        @Override
        public String getPrefix() {
            return null;
        }

        @Override
        public int getDepth() {
            return mDepth;
        }

        @Override
        public int getNamespaceCount(int depth) {
            return 0;
        }

        @Override
        public String getNamespacePrefix(int pos) throws XmlPullParserException {
            throw new XmlPullParserException("no namespaces", this, null);
        }

        @Override
        public String getNamespaceUri(int pos) throws XmlPullParserException {
            throw new XmlPullParserException("no namespaces", this, null);
        }

        @Override
        public String getNamespace(String prefix) {
            return null;
        }

        @Override
        public int getLineNumber() {
            return -1;
        }

        @Override
        public int getColumnNumber() {
            return -1;
        }

        @Override
        public String getPositionDescription() {
            return "recorded MPD event " + mIndex;
        }

        @Override
        public void setFeature(String name, boolean state) throws XmlPullParserException {
            throw new XmlPullParserException("setFeature", this, null);
        }

        @Override
        public boolean getFeature(String name) {
            return false;
        }

        @Override
        public void setProperty(String name, Object value) throws XmlPullParserException {
            throw new XmlPullParserException("setProperty", this, null);
        }

        @Override
        public Object getProperty(String name) {
            return null;
        }

        @Override
        public void setInput(Reader in) throws XmlPullParserException {
            throw new XmlPullParserException("setInput", this, null);
        }

        @Override
        public void setInput(InputStream inputStream, String inputEncoding) throws XmlPullParserException {
            throw new XmlPullParserException("setInput", this, null);
        }

        @Override
        public String getInputEncoding() {
            return "UTF-8";
        }

        @Override
        public void defineEntityReplacementText(String entityName, String replacementText)
                throws XmlPullParserException {
            throw new XmlPullParserException("defineEntityReplacementText", this, null);
        }
    }
}
