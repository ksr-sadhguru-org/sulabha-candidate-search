package org.isha.candidatesearch.search;

import java.util.Locale;

/** One spelling per domain, so "Software and IT", "software & it " and "Software & IT" are the same domain,
 *  and "Trades - Plumbing" is "trades-plumbing". */
public final class Domains {

    private Domains() {
    }

    /** Lowercase, "and" as "&", "-" without spaces around it, single spaces; null or blank stays null. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("\\band\\b", "&")
                .replaceAll("\\s*&\\s*", " & ")
                .replaceAll("[^a-z0-9&\\- ]+", " ")
                .replaceAll("\\s*-\\s*", "-")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
