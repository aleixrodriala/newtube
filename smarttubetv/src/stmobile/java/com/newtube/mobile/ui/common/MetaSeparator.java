package com.newtube.mobile.ui.common;

/**
 * NEWTUBE(separator): the one separator between parts of a meta line ("Channel • 1.2M views •
 * 3 weeks ago"). It is the service's own divider (ServiceHelper.ITEMS_DIVIDER, " • "), which
 * every feed card already shows; the phone-built lines used " · " (downloads, channel rows,
 * pickers) and "  •  " (watch page, comments), three looks for one idea. Display only - the
 * service divider itself is not touched (Video.extractAuthor splits on it).
 */
public final class MetaSeparator {
    public static final String DOT = " • ";

    private MetaSeparator() {
    }
}
