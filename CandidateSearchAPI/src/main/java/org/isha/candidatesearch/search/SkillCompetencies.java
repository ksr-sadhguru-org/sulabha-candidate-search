package org.isha.candidatesearch.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the form's skill competencies field: "Java (2 years), Spring Boot (4 years), Git". */
public final class SkillCompetencies {

    private SkillCompetencies() {
    }

    /** years is null when the entry gave none. */
    public record Item(String name, Double years) {
    }

    private static final Pattern ITEM = Pattern.compile("\\s*([^,(]+?)\\s*(?:\\(([^)]*)\\))?\\s*(?:,|$)");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    public static List<Item> parse(String text) {
        List<Item> items = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return items;
        }
        Matcher m = ITEM.matcher(text);
        while (m.find()) {
            String name = m.group(1).strip();
            if (!name.isEmpty()) {
                items.add(new Item(name, years(m.group(2))));
            }
            if (m.end() >= text.length()) {
                break;
            }
        }
        return items;
    }

    private static Double years(String inParens) {
        if (inParens == null) {
            return null;
        }
        if (inParens.contains("<")) {
            return 0.5; // "<1 year"
        }
        Matcher n = NUMBER.matcher(inParens);
        return n.find() ? Double.parseDouble(n.group(1)) : null;
    }
}
