package com.newtube.mobile.player;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.exoplayer.dash.DashSegmentIndex;
import androidx.media3.exoplayer.dash.manifest.AdaptationSet;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.RangedUri;
import androidx.media3.exoplayer.dash.manifest.Representation;
import androidx.media3.exoplayer.dash.manifest.Representation.MultiSegmentRepresentation;
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SegmentList;
import androidx.media3.exoplayer.dash.manifest.SegmentBase.SegmentTimelineElement;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * media3 port of the legacy TV engine's {@code LiveDashManifestParser} (exoplayer2 2.10.6) - THE
 * app-level fix that makes YouTube live DVR work. Yandex-disk-provenance summary of the problem it
 * solves: YouTube live MPDs use SegmentList + SegmentTimeline where the timeline {@code t} values
 * are absolute media time since stream start while {@code startNumber}/{@code
 * presentationTimeOffset} shift per refresh as the DVR window slides. Stock parsing makes the
 * adaptation sets' segment indexes disagree about period time, so {@code
 * DashMediaSource.processManifest}'s window math (max over sets of start, min over sets of end)
 * produces a huge NEGATIVE window duration - timebar dead, live-edge math garbage.
 *
 * <p>The legacy recipe, ported 1:1 (the SPEC is the legacy behavior, not media3 idioms):</p>
 * <ol>
 *   <li><b>First parse</b> (stream older than its DVR depth, i.e. first segment num &gt; 0):
 *       retain the manifest; zero-base it - {@code Period.startMs = 0} and every representation's
 *       SegmentList gets {@code presentationTimeOffset = 0}, {@code startNumber = 0} (reflection
 *       via {@link Helpers}, the fields are final). {@code Representation.presentationTimeOffsetUs}
 *       (the ctor snapshot used for the chunk sample offset) is deliberately NOT touched - the
 *       legacy parser has that line commented out and shipped that way for years.</li>
 *   <li><b>Refreshes</b>: parse the fresh manifest, append only the NEW tail segments (plus
 *       synthesized {@link SegmentTimelineElement}s extrapolated from the last known element) to
 *       the retained manifest's segment lists, and return the retained manifest - the window grows
 *       monotonically and never REMOVEs from the front.</li>
 *   <li><b>Young streams</b> (first segment num == 0, &lt; DVR depth old): pass the fresh manifest
 *       through untouched (stock behavior is correct there); once the stream outgrows its DVR
 *       depth the append path picks up from the last young manifest.</li>
 * </ol>
 *
 * <p><b>media3-only addition (staleness neutralizer)</b>: unlike 2.10.6, media3's
 * {@code DashMediaSource.onManifestLoadCompleted} treats a dynamic manifest whose
 * {@code publishTimeMs} is &le; an emsg-signalled expiry ({@code expiredManifestPublishTimeUs},
 * fed by {@code PlayerEmsgHandler} from {@code urn:mpeg:dash:event:2012} in-band events) as STALE:
 * it discards the load and, after retries, fails with {@code DashManifestStaleException}. Since
 * this parser returns the SAME retained object on every refresh, its publish time would freeze at
 * the first parse - so each append also copies the fresh manifest's {@code publishTimeMs} onto the
 * retained manifest (reflection, final field). The same-object return is safe for the other 1.10.1
 * dynamic-manifest checks: the removed-period scan compares the manifest against itself (0
 * removed), and {@code DefaultDashChunkSource.RepresentationHolder.copyWithNewRepresentation}
 * computes a 0 segment-num shift when old and new index are the same object.</p>
 *
 * <p>The legacy {@code recreateMissingSegments()}/{@code MultiSegmentRepresentationWrapper}
 * pre-window segment reconstruction is NOT ported: in the legacy source its only call site is
 * commented out ({@code //recreateMissingSegments(newManifest);}) - it is dead code there, so
 * skipping it is behavior-faithful.</p>
 *
 * <p>Don't share/make static: one instance per media source - the retained manifest is per-stream
 * state (same rule as the legacy wiring comment).</p>
 */
@SuppressWarnings("unchecked")
public class LiveDashManifestParser extends DashManifestParser {
    private static final String TAG = LiveDashManifestParser.class.getSimpleName();

    private DashManifest mOldManifest;
    private long mOldSegmentNum;

    @Override
    public DashManifest parse(Uri uri, InputStream inputStream) throws IOException {
        DashManifest manifest = super.parse(uri, inputStream);

        // Not the dynamic SegmentList shape this fix targets (e.g. static post-live DVR, or a
        // structure change upstream): stock behavior. Static manifests never refresh, so the
        // retained-manifest state can't get inconsistent from taking this branch.
        if (!isAppendable(manifest)) {
            return manifest;
        }

        appendManifest(manifest);

        return mOldManifest;
    }

    /** Dynamic + first period + every adaptation set led by a MultiSegment/SegmentList representation. */
    private static boolean isAppendable(DashManifest manifest) {
        if (manifest == null || !manifest.dynamic || manifest.getPeriodCount() == 0) {
            return false;
        }

        Period period = manifest.getPeriod(0);
        if (period.adaptationSets.isEmpty()) {
            return false;
        }

        for (int i = 0; i < period.adaptationSets.size(); i++) {
            List<Representation> representations = period.adaptationSets.get(i).representations;
            if (representations.isEmpty()
                    || !(representations.get(0) instanceof MultiSegmentRepresentation)
                    || !(Helpers.getField(representations.get(0), "segmentBase") instanceof SegmentList)) {
                return false;
            }
        }

        return true;
    }

    private void appendManifest(DashManifest newManifest) {
        if (newManifest == null) {
            return;
        }

        // Optimize ram usage on short streams (< DVR depth): stock manifest already starts at
        // segment 0, nothing to fix. Keep tracking the last segment num - needed later, when the
        // stream outgrows its DVR depth and no longer starts from segment 0.
        if (getFirstSegmentNum(newManifest) == 0) {
            mOldManifest = newManifest;
            mOldSegmentNum = getLastSegmentNum(newManifest);
            return;
        }

        // Streams could have different segment counts per refresh, so take into account the last
        // segment num instead of the first one.
        long newSegmentNum = getLastSegmentNum(newManifest);

        if (mOldManifest == null) {
            // First parse of an already-old stream: retain + zero-base.
            Period newPeriod = newManifest.getPeriod(0);
            Helpers.setField(newPeriod, "startMs", 0L);
            mOldSegmentNum = newSegmentNum;

            for (int i = 0; i < newPeriod.adaptationSets.size(); i++) {
                List<Representation> representations = newPeriod.adaptationSets.get(i).representations;
                for (int j = 0; j < representations.size(); j++) {
                    SegmentList newSegmentList =
                            (SegmentList) Helpers.getField(representations.get(j), "segmentBase");
                    // NOTE: Representation.presentationTimeOffsetUs (ctor snapshot) intentionally
                    // stays untouched - legacy parity.
                    Helpers.setField(newSegmentList, "presentationTimeOffset", 0L);
                    Helpers.setField(newSegmentList, "startNumber", 0L);
                }
            }

            mOldManifest = newManifest;

            return;
        }

        Period oldPeriod = mOldManifest.getPeriod(0);
        Period newPeriod = newManifest.getPeriod(0);

        for (int i = 0; i < oldPeriod.adaptationSets.size(); i++) {
            for (int j = 0; j < oldPeriod.adaptationSets.get(i).representations.size(); j++) {
                appendRepresentation(
                        oldPeriod.adaptationSets.get(i).representations.get(j),
                        newPeriod.adaptationSets.get(i).representations.get(j),
                        newSegmentNum - mOldSegmentNum
                );
            }
        }

        mOldSegmentNum = newSegmentNum;

        // media3 staleness neutralizer (see class javadoc): keep the retained manifest's publish
        // time fresh so PlayerEmsgHandler-signalled expiries can never mark every future refresh
        // stale (DashManifestStaleException loop). 2.10.6 had no such check.
        if (mOldManifest.publishTimeMs != newManifest.publishTimeMs) {
            Helpers.setField(mOldManifest, "publishTimeMs", newManifest.publishTimeMs);
        }
    }

    private static void appendRepresentation(
            Representation oldRepresentation, Representation newRepresentation, long segmentNumShift) {
        if (segmentNumShift <= 0) {
            return;
        }

        SegmentList oldSegmentList = (SegmentList) Helpers.getField(oldRepresentation, "segmentBase");
        SegmentList newSegmentList = (SegmentList) Helpers.getField(newRepresentation, "segmentBase");

        List<RangedUri> oldMediaSegments = (List<RangedUri>) Helpers.getField(oldSegmentList, "mediaSegments");
        List<RangedUri> newMediaSegments = (List<RangedUri>) Helpers.getField(newSegmentList, "mediaSegments");

        if (newMediaSegments.size() < segmentNumShift) {
            // Refresh gap wider than the fresh manifest's whole window (device slept?). Appending
            // a partial tail would break the segment-num<->uri mapping; give up on this source and
            // let the manifest load error surface into the app-level reload (fresh source+parser).
            Log.e(TAG, "appendRepresentation: refresh gap " + segmentNumShift
                    + " > fresh window " + newMediaSegments.size() + "; forcing reload");
            throw new IllegalStateException("Live DVR refresh gap exceeds the manifest window");
        }

        oldMediaSegments.addAll(
                newMediaSegments.subList(newMediaSegments.size() - (int) segmentNumShift, newMediaSegments.size()));

        List<SegmentTimelineElement> oldSegmentTimeline =
                (List<SegmentTimelineElement>) Helpers.getField(oldSegmentList, "segmentTimeline");

        // segmentTimeline duration is the same for all segments: extrapolate from the last element
        if (oldMediaSegments.size() != oldSegmentTimeline.size()) {
            SegmentTimelineElement lastTimeline = oldSegmentTimeline.get(oldSegmentTimeline.size() - 1);
            long lastTimelineDuration = (Long) Helpers.getField(lastTimeline, "duration");
            long lastTimelineStartTime = (Long) Helpers.getField(lastTimeline, "startTime");

            for (int i = 1; i <= segmentNumShift; i++) {
                oldSegmentTimeline.add(new SegmentTimelineElement(
                        lastTimelineStartTime + (lastTimelineDuration * i), lastTimelineDuration));
            }
        }
    }

    private static long getFirstSegmentNum(DashManifest manifest) {
        return getIndex(manifest).getFirstSegmentNum();
    }

    private static long getLastSegmentNum(DashManifest manifest) {
        DashSegmentIndex index = getIndex(manifest);
        return index.getFirstSegmentNum() + index.getSegmentCount(C.TIME_UNSET) - 1;
    }

    private static DashSegmentIndex getIndex(DashManifest manifest) {
        return manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).getIndex();
    }
}
