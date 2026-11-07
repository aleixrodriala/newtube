package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.LanguageSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.NetworkData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SearchData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SponsorBlockData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search across every page of the phone Settings, like the search at the top of Android's own
 * Settings. The index is the tree itself: every page is built once (the same rows the pages show,
 * in the current state) and each switch, choice and link becomes an entry that knows its page,
 * its path and its section's icon.
 *
 * <p>Matching ignores case and accents and works on word starts ("sub" finds "Subscriptions",
 * "subtitulos" finds "Subtítulos"). Every word of the query has to match something. A row's title
 * counts most; then the words people use for it that aren't in its title ({@link #KEYWORDS}:
 * "subtitles" for Captions, "autoplay" for When a video ends), the labels of its options ("dark"
 * finds Theme), its summary, and last the pages it sits in.</p>
 */
final class SettingsSearch {
    private static final String TAG = SettingsSearch.class.getSimpleName();
    private static final String SEPARATOR = " › ";
    private static final ExecutorService BUILDER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_RESULTS = 50;

    /**
     * Words people search with that a row's title doesn't contain: {title, words}. The words are
     * per language (strings_settings_search.xml), so the Spanish list holds Spanish words.
     */
    private static final int[][] KEYWORDS = {
            {R.string.mobile_settings_account, R.string.mobile_settings_search_kw_account},
            {R.string.mobile_theme, R.string.mobile_settings_search_kw_theme},
            {R.string.mobile_theme_pure_black, R.string.mobile_settings_search_kw_pure_black},
            {R.string.mobile_settings_country, R.string.mobile_settings_search_kw_country},
            {R.string.mobile_settings_start_screen, R.string.mobile_settings_search_kw_start_screen},
            {R.string.mobile_settings_interface_size, R.string.mobile_settings_search_kw_interface_size},
            {R.string.mobile_settings_tabs, R.string.mobile_settings_search_kw_tabs},
            {R.string.mobile_settings_hidden, R.string.mobile_settings_search_kw_hidden},
            {R.string.mobile_settings_video_menu, R.string.mobile_settings_search_kw_video_menu},
            {R.string.mobile_settings_channels_order, R.string.mobile_settings_search_kw_channels_order},
            {R.string.mobile_settings_watch_history, R.string.mobile_settings_search_kw_watch_history},
            {R.string.mobile_settings_gesture_levels, R.string.mobile_settings_search_kw_gesture_levels},
            {R.string.mobile_settings_gesture_seek, R.string.mobile_settings_search_kw_gesture_seek},
            {R.string.mobile_settings_video_ends, R.string.mobile_settings_search_kw_video_ends},
            {R.string.mobile_settings_background, R.string.mobile_settings_search_kw_background},
            {R.string.mobile_settings_natural_voice, R.string.mobile_settings_search_kw_natural_voice},
            {R.string.mobile_settings_audio_focus, R.string.mobile_settings_search_kw_audio_focus},
            {R.string.mobile_settings_hide_related, R.string.mobile_settings_search_kw_hide_related},
            {R.string.mobile_settings_dislikes, R.string.mobile_settings_search_kw_dislikes},
            {R.string.mobile_settings_quality, R.string.mobile_settings_search_kw_quality},
            {R.string.mobile_settings_default_quality, R.string.mobile_settings_search_kw_quality},
            {R.string.mobile_settings_loudness, R.string.mobile_settings_search_kw_loudness},
            {R.string.mobile_settings_player_volume, R.string.mobile_settings_search_kw_player_volume},
            {R.string.mobile_settings_captions, R.string.mobile_settings_search_kw_captions},
            {R.string.content_block_provider, R.string.mobile_settings_search_kw_sponsorblock},
            {R.string.dearrow_provider, R.string.mobile_settings_search_kw_dearrow},
            {R.string.mobile_settings_backup, R.string.mobile_settings_search_kw_backup},
            {R.string.mobile_settings_video_format, R.string.mobile_settings_search_kw_video_format},
            {R.string.mobile_settings_buffer, R.string.mobile_settings_search_kw_buffer},
            {R.string.mobile_settings_dns, R.string.mobile_settings_search_kw_dns},
            {R.string.mobile_settings_conscrypt, R.string.mobile_settings_search_kw_conscrypt},
            {R.string.mobile_settings_multi_profiles, R.string.mobile_settings_search_kw_multi_profiles},
            {R.string.check_for_updates, R.string.mobile_settings_search_kw_updates},
            {R.string.diagnostic_log_send, R.string.mobile_settings_search_kw_diagnostic_log},
    };

    /** One row that search can find. */
    static final class Entry {
        /** The page the row is on. */
        final String pageId;
        /** For a row that opens a page: that page (the result opens it instead of pointing at the row). */
        @Nullable final String opens;
        /** The account row: its "page" is the accounts sheet. */
        final boolean account;
        final CharSequence title;
        /** "Tabs and feeds › Hidden videos › Live streams": the pages and the group the row sits in. */
        final String path;
        /** The icon of the top-level section the row belongs to. */
        @DrawableRes final int icon;
        final int order;

        private final String mTitle;
        private final String mKeywords;
        private final String mOptions;
        private final String mSummary;
        private final String mPath;

        Entry(String pageId, @Nullable String opens, boolean account, CharSequence title, String path, String pathWords,
              @DrawableRes int icon, int order, @Nullable String keywords, String options, @Nullable CharSequence summary) {
            this.pageId = pageId;
            this.opens = opens;
            this.account = account;
            this.title = title;
            this.path = path;
            this.icon = icon;
            this.order = order;
            mTitle = normalize(title);
            mKeywords = normalize(keywords);
            mOptions = normalize(options);
            mSummary = normalize(summary);
            mPath = normalize(path + " " + pathWords);
        }

        /** 0 when a word of the query matches nothing, else higher for a better match. */
        int score(List<String> words, String query) {
            int total = 0;
            for (String word : words) {
                int best = 0;
                String start = " " + word;
                if (mTitle.contains(start)) {
                    best = 100;
                } else if (word.length() >= 3 && mTitle.contains(word)) {
                    best = 50;
                }
                if (best < 60 && mKeywords.contains(start)) {
                    best = 60;
                }
                if (best < 35 && mOptions.contains(start)) {
                    best = 35;
                }
                if (best < 25 && mSummary.contains(start)) {
                    best = 25;
                }
                if (best < 15 && mPath.contains(start)) {
                    best = 15;
                }
                if (best == 0) {
                    return 0;
                }
                total += best;
            }
            if (mTitle.startsWith(" " + query)) {
                total += 40; // the title starts with what was typed
            }
            if (opens != null) {
                total += 5; // a whole section before a row of it
            }
            return total;
        }
    }

    private final List<Entry> mEntries;

    private SettingsSearch(List<Entry> entries) {
        mEntries = entries;
    }

    /**
     * Builds the index on a background thread and hands it over on the main one. Building every
     * page takes a few hundred ms (more the first time: the language and country lists, the caption
     * styles), which would hold up the search page's opening and its keyboard.
     */
    static void buildAsync(@NonNull Context context, @NonNull OnBuilt onBuilt) {
        warmUp(context);
        BUILDER.execute(() -> {
            long start = SystemClock.elapsedRealtime();
            SettingsSearch search = build(context);
            Log.d(TAG, "Index of %s rows built in %s ms", search.mEntries.size(), SystemClock.elapsedRealtime() - start);
            MAIN.post(() -> onBuilt.onBuilt(search));
        });
    }

    /**
     * The singletons the pages read are created lazily and without a lock: create them here, on the
     * main thread, so the worker only reads them and never races the screens to create its own.
     */
    private static void warmUp(Context context) {
        PlayerData.instance(context);
        PlayerTweaksData.instance(context);
        GeneralData.instance(context);
        MainUIData.instance(context);
        SearchData.instance(context);
        SponsorBlockData.instance(context);
        DeArrowData.instance(context);
        NetworkData.instance(context);
        SidebarService.instance(context);
        LanguageSettingsPresenter.instance(context);
        AppDataSourceManager.instance();
        MediaServiceManager.instance();
        MediaServiceData.instance();
    }

    interface OnBuilt {
        void onBuilt(@NonNull SettingsSearch search);
    }

    /** Walks the tree from the top level and indexes every row a person can act on. */
    @NonNull
    static SettingsSearch build(@NonNull Context context) {
        Map<String, String> keywords = new HashMap<>();
        for (int[] pair : KEYWORDS) {
            keywords.put(context.getString(pair[0]), context.getString(pair[1]));
        }

        List<Entry> entries = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        // {pageId, parent path, section icon, the words of the links that led here}
        Deque<Object[]> pending = new ArrayDeque<>();
        pending.add(new Object[] {SettingsPages.ROOT, "", 0, ""});
        visited.add(SettingsPages.ROOT);

        while (!pending.isEmpty()) {
            Object[] next = pending.poll();
            String pageId = (String) next[0];
            String parentPath = (String) next[1];
            int sectionIcon = (Integer) next[2];
            String inherited = (String) next[3];
            boolean root = SettingsPages.ROOT.equals(pageId);

            SettingsPages.Page page;
            try {
                page = SettingsPages.build(context, pageId);
            } catch (RuntimeException e) {
                Log.e(TAG, "Page %s left out of the search: %s", pageId, e);
                continue; // one page that can't be built here shouldn't take the search down
            }
            String pagePath = root ? String.valueOf(page.title)
                    : parentPath.isEmpty() ? String.valueOf(page.title) : parentPath + SEPARATOR + page.title;
            CharSequence group = null;

            for (SettingsRow row : page.rows) {
                switch (row.kind) {
                    case SettingsRow.KIND_HEADER:
                        group = row.title;
                        continue;
                    case SettingsRow.KIND_DIVIDER:
                        group = null;
                        continue;
                    case SettingsRow.KIND_LINK:
                    case SettingsRow.KIND_SWITCH:
                    case SettingsRow.KIND_CHOICE:
                        break;
                    default:
                        continue;
                }

                int icon = root ? row.icon : sectionIcon;
                String path = root || group == null ? pagePath : pagePath + SEPARATOR + group;
                StringBuilder options = new StringBuilder();
                if (row.options != null) {
                    for (SettingsRow.Option option : row.options) {
                        options.append(option.label).append(' ');
                    }
                }
                CharSequence summary = null;
                try {
                    summary = row.summaryText();
                } catch (RuntimeException e) {
                    // A summary that can't be read now just isn't searchable.
                }
                // Signed in, the account row is titled with the account's name: find its words by its icon.
                boolean account = root && row.icon == R.drawable.ic_settings_account;
                String words = account
                        ? context.getString(R.string.mobile_settings_account) + " "
                                + keywords.get(context.getString(R.string.mobile_settings_account))
                        : keywords.get(String.valueOf(row.title));
                // A row also answers, weakly, to its section's words ("subtitles" finds Captions' rows).
                entries.add(new Entry(pageId, row.page, account, row.title, path, inherited, icon, entries.size(),
                        words, options.toString(), summary));

                if (row.page != null && visited.add(row.page)) {
                    pending.add(new Object[] {row.page, root ? "" : pagePath, icon,
                            words != null ? inherited + " " + words : inherited});
                }
            }
        }
        return new SettingsSearch(entries);
    }

    /** The rows that match, best first. */
    @NonNull
    List<Entry> find(@Nullable CharSequence query) {
        String normalized = normalize(query).trim();
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> words = new ArrayList<>();
        for (String word : normalized.split(" +")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }

        List<Entry> hits = new ArrayList<>();
        Map<Entry, Integer> scores = new HashMap<>();
        for (Entry entry : mEntries) {
            int score = entry.score(words, normalized);
            if (score > 0) {
                hits.add(entry);
                scores.put(entry, score);
            }
        }
        Collections.sort(hits, (a, b) -> {
            int byScore = Integer.compare(scores.get(b), scores.get(a));
            return byScore != 0 ? byScore : Integer.compare(a.order, b.order);
        });
        return hits.size() > MAX_RESULTS ? hits.subList(0, MAX_RESULTS) : hits;
    }

    /** The title with the parts the query matched in bold (word starts, as the search matches). */
    @NonNull
    static CharSequence highlight(@NonNull CharSequence title, @Nullable CharSequence query) {
        int[][] map = new int[1][];
        String folded = foldMapped(title, map);
        SpannableString result = new SpannableString(title);
        for (String word : normalize(query).trim().split(" +")) {
            if (word.isEmpty()) {
                continue;
            }
            int from = 0;
            while (true) {
                int at = folded.indexOf(word, from);
                if (at < 0) {
                    break;
                }
                boolean wordStart = at == 0 || !Character.isLetterOrDigit(folded.charAt(at - 1));
                if (wordStart) {
                    result.setSpan(new StyleSpan(Typeface.BOLD), map[0][at], map[0][at + word.length() - 1] + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                }
                from = at + 1;
            }
        }
        return result;
    }

    /**
     * " lower case words without accents " with a space at both ends, so a word start is
     * {@code contains(" " + word)}. Anything that isn't a letter or a digit separates words.
     */
    static String normalize(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) {
            return " ";
        }
        String folded = foldAll(text);
        StringBuilder out = new StringBuilder(folded.length() + 2).append(' ');
        boolean space = true;
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
                space = false;
            } else if (!space) {
                out.append(' ');
                space = true;
            }
        }
        if (!space) {
            out.append(' ');
        }
        return out.toString();
    }

    /** Lower case without accents, the whole string in one pass (the index: speed over alignment). */
    private static String foldAll(CharSequence text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /**
     * {@link #foldAll}, char by char, remembering for each folded char the char of {@code text} it
     * came from ({@code map[0]}), so a match found in the folded text can be marked in the original.
     */
    private static String foldMapped(CharSequence text, int[][] map) {
        StringBuilder out = new StringBuilder(text.length());
        int[] from = new int[text.length() * 3 + 1];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String decomposed = c < 0x80 ? null : Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
            int count = decomposed == null ? 1 : decomposed.length();
            for (int k = 0; k < count; k++) {
                char d = decomposed == null ? c : decomposed.charAt(k);
                if (Character.getType(d) == Character.NON_SPACING_MARK) {
                    continue;
                }
                if (out.length() == from.length) {
                    from = Arrays.copyOf(from, from.length * 2);
                }
                from[out.length()] = i;
                out.append(Character.toLowerCase(d));
            }
        }
        map[0] = from;
        return out.toString();
    }
}
