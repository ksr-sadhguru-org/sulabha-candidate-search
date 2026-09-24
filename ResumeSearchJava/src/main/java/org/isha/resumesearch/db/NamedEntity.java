package org.isha.resumesearch.db;

import java.util.List;

/** A skill or role name plus its synonyms, ahead of normalization against entity_synonyms. */
public record NamedEntity(String canonical, List<String> synonyms) {
}
