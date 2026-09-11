package com.newtube.mobile.downloads;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

import java.util.List;

/** Minimal MediaFormat for the selection tests: only the fields the picker reads are settable. */
final class FakeMediaFormat implements MediaFormat {
    private final String mItag;
    private final String mMime;
    private final int mHeight;
    private final String mBitrate;
    private final String mClen;
    private final String mUrl;
    private String mAudioTrackId;
    private boolean mDrc;
    private String mQualityLabel;

    FakeMediaFormat(String itag, String mime, int height, int bitrate, long clen) {
        mItag = itag;
        mMime = mime;
        mHeight = height;
        mBitrate = String.valueOf(bitrate);
        mClen = clen < 0 ? null : String.valueOf(clen);
        mUrl = "https://rr1.googlevideo.com/videoplayback?itag=" + itag;
    }

    static FakeMediaFormat video(String itag, int height, int bitrate, long clen) {
        FakeMediaFormat f = new FakeMediaFormat(itag, "video/mp4; codecs=\"avc1.640028\"", height, bitrate, clen);
        f.mQualityLabel = height + "p";
        return f;
    }

    static FakeMediaFormat vp9(String itag, int height, int bitrate, long clen) {
        FakeMediaFormat f = new FakeMediaFormat(itag, "video/webm; codecs=\"vp9\"", height, bitrate, clen);
        f.mQualityLabel = height + "p";
        return f;
    }

    static FakeMediaFormat audio(String itag, int bitrate, long clen) {
        return new FakeMediaFormat(itag, "audio/mp4; codecs=\"mp4a.40.2\"", -1, bitrate, clen);
    }

    static FakeMediaFormat opus(String itag, int bitrate, long clen) {
        return new FakeMediaFormat(itag, "audio/webm; codecs=\"opus\"", -1, bitrate, clen);
    }

    FakeMediaFormat track(String id) {
        mAudioTrackId = id;
        return this;
    }

    FakeMediaFormat drc() {
        mDrc = true;
        return this;
    }

    FakeMediaFormat label(String label) {
        mQualityLabel = label;
        return this;
    }

    @Override public int getFormatType() { return FORMAT_TYPE_DASH; }
    @Override public String getUrl() { return mUrl; }
    @Override public String getMimeType() { return mMime; }
    @Override public String getITag() { return mItag; }
    @Override public boolean isDrc() { return mDrc; }
    @Override public String getClen() { return mClen; }
    @Override public String getBitrate() { return mBitrate; }
    @Override public String getProjectionType() { return null; }
    @Override public String getXtags() { return null; }
    @Override public String getAudioTrackId() { return mAudioTrackId; }
    @Override public int getWidth() { return -1; }
    @Override public int getHeight() { return mHeight; }
    @Override public String getIndex() { return null; }
    @Override public String getInit() { return null; }
    @Override public String getFps() { return null; }
    @Override public String getLmt() { return null; }
    @Override public String getQualityLabel() { return mQualityLabel; }
    @Override public String getFormat() { return null; }
    @Override public boolean isOtf() { return false; }
    @Override public String getOtfInitUrl() { return null; }
    @Override public String getOtfTemplateUrl() { return null; }
    @Override public String getLanguage() { return null; }
    @Override public int getTargetDurationSec() { return 0; }
    @Override public int getMaxDvrDurationSec() { return 0; }
    @Override public int getApproxDurationMs() { return 0; }
    @Override public String getQuality() { return null; }
    @Override public String getSignature() { return null; }
    @Override public String getAudioSamplingRate() { return null; }
    @Override public String getSourceUrl() { return null; }
    @Override public List<String> getSegmentUrlList() { return null; }
    @Override public List<String> getGlobalSegmentList() { return null; }
    @Override public int compareTo(MediaFormat o) { return 0; }
}
