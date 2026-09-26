package org.isha.candidatesearch.search;

import java.util.Locale;

/** One spelling per field, so "Software and IT", "software & it " and "Software & IT" are the same field. */
public final class Fields {

    private Fields() {
    }

    /** Lowercase, "and" as "&", single spaces; null or blank stays null. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("\\band\\b", "&")
                .replaceAll("\\s*&\\s*", " & ")
                .replaceAll("[^a-z0-9& ]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
