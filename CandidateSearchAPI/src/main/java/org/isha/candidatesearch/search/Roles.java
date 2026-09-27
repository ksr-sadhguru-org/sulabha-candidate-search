package org.isha.candidatesearch.search;

import java.util.List;
import java.util.Locale;

/**
 * The fixed list of roles - the kind of work a job is, whatever its subject (a music teacher and a maths tutor are
 * both "teacher"). Fixed so searches compare roles exactly; the subject side is the growing domain list.
 */
public final class Roles {

    private Roles() {
    }

    public static final String OTHER = "other";

    public static final List<String> ALL = List.of(
            "engineer / developer", "tester / qa", "technician", "tradesperson", "teacher", "performer", "designer",
            "writer", "professional", "manager", "healthcare worker", "service worker", "driver / operator",
            "researcher", "trainee", OTHER);

    /** The listed role this names ("Teacher", "tester/QA"), or null when it names none. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String r = raw.toLowerCase(Locale.ROOT).replaceAll("\\s*/\\s*", " / ").replaceAll("\\s+", " ").strip();
        return ALL.contains(r) ? r : null;
    }
}
