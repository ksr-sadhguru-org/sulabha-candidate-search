package org.isha.candidatesearch.search;

import org.isha.candidatesearch.dto.ParsedQuery.Location;

import java.util.Arrays;
import java.util.List;

/** Splits a "City, State, Country" location (as the LLM writes it into the form) into its parts. */
public final class Locations {

    private Locations() {
    }

    public static Location parse(String text) {
        if (text == null || text.isBlank()) {
            return new Location(null, null, null);
        }
        List<String> parts = Arrays.stream(text.split(",")).map(String::strip).filter(p -> !p.isEmpty()).toList();
        return switch (parts.size()) {
            case 0 -> new Location(null, null, null);
            case 1 -> new Location(parts.get(0), null, null);
            // "Dubai, United Arab Emirates" - a city-state style location with no state part
            case 2 -> new Location(parts.get(0), null, parts.get(1));
            default -> new Location(parts.get(0), parts.get(parts.size() - 2), parts.get(parts.size() - 1));
        };
    }

    /** "Coimbatore, Tamil Nadu, India" from the parts that are present. */
    public static String format(Location location) {
        if (location == null) {
            return null;
        }
        String joined = String.join(", ", java.util.stream.Stream.of(location.city(), location.state(), location.country())
                .filter(p -> p != null && !p.isBlank()).toList());
        return joined.isEmpty() ? null : joined;
    }
}
