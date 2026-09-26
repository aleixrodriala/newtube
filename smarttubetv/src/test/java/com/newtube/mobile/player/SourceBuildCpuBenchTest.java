package com.newtube.mobile.player;

import android.app.Application;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.dash.manifest.AdaptationSet;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.Representation;
import androidx.media3.exoplayer.source.MediaSource;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NEWTUBE(open-cpu): offline micro-benchmark of the app-side DASH source build that sits between
 * the {@code info} and {@code prepare} NetPath milestones: generated MPD, media3's parse, and the
 * MediaSource. Opt-in (skipped unless NEWTUBE_BENCH_DIR names a directory of /player JSON
 * fixtures). Writes a canonical dump of the resulting manifest per fixture to
 * $NEWTUBE_BENCH_DIR/out/NAME.app.LABEL.txt so two builds can be diffed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SourceBuildCpuBenchTest {
    private static final Uri BASE = Uri.parse("https://youtube.com/generated.mpd");
    private static final String[] PHASES = {"gen", "parse", "source", "total", "rec", "replay", "direct"};

    @Test
    public void benchmark() throws Exception {
        String dirName = System.getenv("NEWTUBE_BENCH_DIR");
        Assume.assumeTrue("set NEWTUBE_BENCH_DIR to run", dirName != null);
        String label = System.getenv("NEWTUBE_BENCH_LABEL");
        label = label != null ? label : "run";
        int warmup = Integer.parseInt(envOr("NEWTUBE_BENCH_WARMUP", "200"));
        int iterations = Integer.parseInt(envOr("NEWTUBE_BENCH_ITER", "200"));
        File dir = new File(dirName);
        File outDir = new File(dir, "out");
        outDir.mkdirs();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        Arrays.sort(files);

        // Retrofit/JsonPath are not on this module's compile classpath: reach the real converter
        // (JsonPathConverterFactory -> JsonPathResponseBodyConverter.convert(InputStream)) reflectively.
        Class<?> factoryType = Class.forName(
                "com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory");
        Object factory = factoryType.getMethod("create").invoke(null);
        java.lang.reflect.Method bodyConverter = null;
        for (java.lang.reflect.Method m : factoryType.getMethods()) {
            if (m.getName().equals("responseBodyConverter")) bodyConverter = m;
        }
        Object converter = bodyConverter.invoke(factory, VideoInfo.class, null, null);
        java.lang.reflect.Method convert = converter.getClass().getMethod("convert", InputStream.class);
        List<String> names = new ArrayList<>();
        List<MediaItemFormatInfo> infos = new ArrayList<>();
        for (File file : files) {
            VideoInfo info = (VideoInfo) convert.invoke(converter,
                    new ByteArrayInputStream(Files.readAllBytes(file.toPath())));
            info.setClient(file.getName().startsWith("tvhtml5") ? AppClient.TV_TIZEN : AppClient.VISIONOS);
            names.add(file.getName().replace(".json", ""));
            infos.add(YouTubeMediaItemFormatInfo.from(info));
        }

        long[][][] samples = new long[infos.size()][PHASES.length][iterations];
        long[][] first = new long[infos.size()][PHASES.length];
        for (int i = 0; i < infos.size(); i++) {
            StringBuilder dump = new StringBuilder();
            buildOnce(infos.get(i), first[i], dump);
            Files.write(new File(outDir, names.get(i) + ".app." + label + ".txt").toPath(),
                    dump.toString().getBytes(StandardCharsets.UTF_8));
        }
        long[] scratch = new long[PHASES.length];
        for (int w = 0; w < warmup; w++) {
            for (MediaItemFormatInfo info : infos) buildOnce(info, scratch, null);
        }
        for (int it = 0; it < iterations; it++) {
            for (int i = 0; i < infos.size(); i++) {
                buildOnce(infos.get(i), scratch, null);
                for (int p = 0; p < PHASES.length; p++) samples[i][p][it] = scratch[p];
            }
        }
        StringBuilder report = new StringBuilder("label=" + label + " (app source build)\n");
        report.append(String.format("%-28s", "fixture"));
        for (String phase : PHASES) report.append(String.format("%16s", phase));
        report.append('\n');
        for (int i = 0; i < infos.size(); i++) {
            report.append(String.format("%-28s", names.get(i)));
            for (int p = 0; p < PHASES.length; p++) {
                long[] s = samples[i][p].clone();
                Arrays.sort(s);
                report.append(String.format("%16s", String.format("%.3f/%.3f",
                        s[s.length / 2] / 1e6, s[s.length * 3 / 4] / 1e6)));
            }
            report.append('\n').append(String.format("%-28s", "  first"));
            for (int p = 0; p < PHASES.length; p++) report.append(String.format("%16.3f", first[i][p] / 1e6));
            report.append('\n');
        }
        System.out.println(report);
        Files.write(new File(outDir, "bench.app." + label + ".txt").toPath(),
                report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void buildOnce(MediaItemFormatInfo info, long[] out, StringBuilder dump)
            throws Exception {
        // The text route, as Media3SourceFactory ran it before (and still does as the fallback).
        long t0 = System.nanoTime();
        InputStream mpd = info.createMpdStream();
        long t1 = System.nanoTime();
        DashManifest manifest = new Media3SourceFactory.StaticDashManifestParser().parse(BASE, mpd);
        long t2 = System.nanoTime();
        MediaSource source = source(manifest);
        long t3 = System.nanoTime();
        out[0] = t1 - t0;
        out[1] = t2 - t1;
        out[2] = t3 - t2;
        out[3] = t3 - t0;

        // The direct route (DirectMpd): recorded builder calls replayed into the same parser.
        long d0 = System.nanoTime();
        DirectMpd.Recorder recorder = DirectMpd.record(info);
        long d1 = System.nanoTime();
        DashManifest direct = recorder != null
                ? new Media3SourceFactory.StaticDashManifestParser().parse(recorder.replay(), BASE) : null;
        MediaSource directSource = direct != null ? source(direct) : null;
        long d2 = System.nanoTime();
        out[4] = d1 - d0;
        out[5] = d2 - d1;
        out[6] = d2 - d0;
        if (dump != null) {
            String xml = describe(manifest);
            dump.append(xml).append(source != null).append('\n');
            dump.append("direct ").append(direct == null ? "declined"
                    : describe(direct).equals(xml) ? "IDENTICAL" : "DIFFERENT\n" + describe(direct))
                    .append(' ').append(directSource != null).append('\n');
        }
    }

    private static MediaSource source(DashManifest manifest) {
        return new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(new DefaultHttpDataSource.Factory()), null)
                .createMediaSource(manifest, new MediaItem.Builder().setMediaId("bench")
                        .setUri(BASE).setMimeType(MimeTypes.APPLICATION_MPD).build());
    }

    /** Every field of the parsed manifest a player reads, in document order. */
    static String describe(DashManifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append("mpd dynamic=").append(manifest.dynamic).append(" dur=").append(manifest.durationMs)
                .append(" minBuf=").append(manifest.minBufferTimeMs).append(" ast=")
                .append(manifest.availabilityStartTimeMs).append(" mup=").append(manifest.minUpdatePeriodMs)
                .append(" tsb=").append(manifest.timeShiftBufferDepthMs).append(" spd=")
                .append(manifest.suggestedPresentationDelayMs).append(" pub=").append(manifest.publishTimeMs)
                .append(" loc=").append(manifest.location).append(" utc=").append(manifest.utcTiming)
                .append(" svc=").append(manifest.serviceDescription).append(" prog=")
                .append(manifest.programInformation).append(" periods=").append(manifest.getPeriodCount())
                .append('\n');
        for (int p = 0; p < manifest.getPeriodCount(); p++) {
            Period period = manifest.getPeriod(p);
            sb.append(" period id=").append(period.id).append(" start=").append(period.startMs)
                    .append(" events=").append(period.eventStreams.size()).append(" asset=")
                    .append(period.assetIdentifier).append(" durUs=").append(manifest.getPeriodDurationUs(p))
                    .append('\n');
            for (AdaptationSet set : period.adaptationSets) {
                sb.append("  set id=").append(set.id).append(" type=").append(set.type)
                        .append(" acc=").append(set.accessibilityDescriptors)
                        .append(" ess=").append(set.essentialProperties)
                        .append(" sup=").append(set.supplementalProperties).append('\n');
                for (Representation r : set.representations) {
                    sb.append("   rep ").append(r.getClass().getSimpleName()).append(" rev=")
                            .append(r.revisionId).append(" pto=").append(r.presentationTimeOffsetUs)
                            .append(" format=").append(r.format).append(" fmtHash=").append(r.format.hashCode())
                            .append(" label=").append(r.format.label).append(" labels=").append(r.format.labels)
                            .append(" role=").append(r.format.roleFlags).append(" sel=")
                            .append(r.format.selectionFlags).append(" container=")
                            .append(r.format.containerMimeType).append(" fps=").append(r.format.frameRate)
                            .append(" avg=").append(r.format.averageBitrate).append(" peak=")
                            .append(r.format.peakBitrate).append(" ch=").append(r.format.channelCount)
                            .append(" acc=").append(r.format.accessibilityChannel)
                            .append(" drm=").append(r.format.drmInitData)
                            .append(" meta=").append(r.format.metadata)
                            .append(" cryptoType=").append(r.format.cryptoType)
                            .append(" inband=").append(r.inbandEventStreams)
                            .append(" ess=").append(r.essentialProperties)
                            .append(" sup=").append(r.supplementalProperties)
                            .append(" cacheKey=").append(r.getCacheKey())
                            .append(" formatFields=").append(fields(r.format));
                    for (androidx.media3.exoplayer.dash.manifest.BaseUrl base : r.baseUrls) {
                        sb.append(" base=").append(base.url).append('|').append(base.serviceLocation)
                                .append('|').append(base.priority).append('|').append(base.weight);
                    }
                    androidx.media3.exoplayer.dash.manifest.RangedUri init = r.getInitializationUri();
                    androidx.media3.exoplayer.dash.manifest.RangedUri index = r.getIndexUri();
                    sb.append(" init=").append(init != null ? init.resolveUriString("") + "@" + init.start + "+" + init.length : null);
                    sb.append(" index=").append(index != null ? index.resolveUriString("") + "@" + index.start + "+" + index.length : null);
                    androidx.media3.exoplayer.dash.DashSegmentIndex segIndex = r.getIndex();
                    sb.append(" segIndex=").append(segIndex != null ? segIndex.getClass().getSimpleName()
                            + ":" + segIndex.isExplicit() : null);
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Every instance field of a media3 value object, reflectively (arrays expanded). */
    static String fields(Object value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("{");
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object v = field.get(value);
                    String text = v instanceof Object[] ? java.util.Arrays.deepToString((Object[]) v)
                            : v instanceof byte[] ? java.util.Arrays.toString((byte[]) v)
                            : String.valueOf(v);
                    sb.append(field.getName()).append('=').append(text).append(';');
                } catch (ReflectiveOperationException | RuntimeException e) {
                    sb.append(field.getName()).append("=?;");
                }
            }
        }
        return sb.append('}').toString();
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }
}
