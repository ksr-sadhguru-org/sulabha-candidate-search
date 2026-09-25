package org.isha.candidatesearch.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one normalization used for every searchable phrase - profile terms at upload and query terms at search -
 * so both sides always agree: lowercase, "&" -> "and", punctuation to spaces (keeping "+", "#" and "." for
 * "c++", "c#", ".net", "node.js"), dotted abbreviations collapsed ("b.e." -> "be"), and plurals made singular
 * ("teachers" -> "teacher").
 */
public final class Terms {

    private Terms() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^a-z0-9+#.]+", " ").strip();
        List<String> words = new ArrayList<>();
        for (String word : cleaned.split(" +")) {
            String w = collapseDots(word);
            if (!w.isEmpty()) {
                words.add(singular(w));
            }
        }
        return String.join(" ", words);
    }

    /** True when {@code term} is {@code query} or ends with it as whole words: "music teacher" matches "teacher". */
    public static boolean matches(String term, String query) {
        return term.equals(query) || term.endsWith(" " + query);
    }

    /** "sr." -> "sr", "b.e" -> "be", "m.sc" -> "msc"; ".net", "node.js" and "asp.net" keep their dots. */
    private static String collapseDots(String word) {
        String w = word;
        while (w.endsWith(".")) {
            w = w.substring(0, w.length() - 1);
        }
        if (w.startsWith(".") || !w.contains(".")) {
            return w;
        }
        for (String segment : w.split("\\.")) {
            if (segment.length() > 2) {
                return w;
            }
        }
        return w.replace(".", "");
    }

    private static String singular(String w) {
        if (w.length() <= 4 || !w.chars().allMatch(Character::isLetter)) {
            return w;
        }
        if (w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("sses") || w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("ss") || w.endsWith("us") || w.endsWith("is") || w.endsWith("ics")) {
            return w;
        }
        return w.endsWith("s") ? w.substring(0, w.length() - 1) : w;
    }
}
