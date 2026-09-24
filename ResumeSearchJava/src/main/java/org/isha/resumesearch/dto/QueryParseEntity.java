package org.isha.resumesearch.dto;

import java.util.List;

public record QueryParseEntity(String canonical, List<String> synonyms) {
}
