package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Locale;

/** NEWTUBE(audio-label): raw audio-track tags become language names, display only. */
public class AudioTrackLabelTest {
    private static String en(String raw) {
        return AudioTrackLabel.format(raw, Locale.ENGLISH, "original", "dubbed", "audio description");
    }

    @Test
    public void originalAndDubbedTracksReadAsLanguageNames() {
        assertEquals("English (original)", en("En (original)"));
        assertEquals("Portuguese (dubbed)", en("pt (dubbed)"));
    }

    @Test
    public void regionTagsIncludingUnicodeHyphenAreUnderstood() {
        assertEquals("English (United States)", en("en‐US"));
        assertEquals("Portuguese (Brazil, dubbed)", en("pt-BR (dubbed)"));
    }

    @Test
    public void namesFollowTheAppLanguage() {
        assertEquals("Inglés (original)",
                AudioTrackLabel.format("en (original)", new Locale("es"), "original", "doblado", "audiodescripción"));
    }

    @Test
    public void somethingThatIsNotATagIsLeftAlone() {
        assertEquals("Default", en("Default"));
        assertEquals("", en(""));
    }

    @Test
    public void unknownRoleIsKeptVerbatim() {
        assertEquals("German (secondary)", en("de (secondary)"));
    }
}
