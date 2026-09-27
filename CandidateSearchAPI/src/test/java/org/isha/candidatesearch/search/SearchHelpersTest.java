package org.isha.candidatesearch.search;

import org.isha.candidatesearch.dto.ParsedQuery.Location;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchHelpersTest {

    @Test
    void normalizesTermsTheSameWayOnBothSides() {
        assertEquals("teacher", Terms.normalize("Teachers"));
        assertEquals("be", Terms.normalize("B.E."));
        assertEquals("sr java developer", Terms.normalize("Sr. Java Developer"));
        assertEquals("c++ programmer", Terms.normalize("C++ programmers"));
        assertEquals(".net developer", Terms.normalize(".NET Developer"));
        assertEquals("node.js", Terms.normalize("Node.js"));
        assertEquals("yoga class", Terms.normalize("yoga classes"));
        assertEquals("analytics", Terms.normalize("Analytics"));
        assertEquals("research and development", Terms.normalize("Research & Development"));
    }

    @Test
    void normalizesFieldNames() {
        assertEquals("software & it", Fields.normalize("Software and IT"));
        assertEquals("trades-plumbing", Fields.normalize("Trades - Plumbing"));
        assertEquals("trades-carpentry & woodwork", Fields.normalize("trades-Carpentry and Woodwork"));
        assertEquals("other trades", Fields.normalize(" Other  Trades "));
        assertNull(Fields.normalize("  "));
    }

    @Test
    void matchesWholeTrailingWordsOnly() {
        assertTrue(Terms.matches("music teacher", "teacher"));
        assertTrue(Terms.matches("teacher", "teacher"));
        assertFalse(Terms.matches("javascript", "java"));
        assertFalse(Terms.matches("electrical engineer", "electrician"));
    }

    @Test
    void parsesLocations() {
        assertEquals(new Location("Coimbatore", "Tamil Nadu", "India"), Locations.parse("Coimbatore, Tamil Nadu, India"));
        assertEquals(new Location("Dubai", null, "United Arab Emirates"), Locations.parse("Dubai, United Arab Emirates"));
        assertEquals(new Location("Pune", null, null), Locations.parse("Pune"));
        assertEquals("Tamil Nadu, India", Locations.format(new Location(null, "Tamil Nadu", "India")));
    }

    @Test
    void parsesSkillCompetencies() {
        assertEquals(List.of(new SkillCompetencies.Item("Java", 2.0), new SkillCompetencies.Item("Spring Boot", 4.0),
                        new SkillCompetencies.Item("Git", null), new SkillCompetencies.Item("C++", 0.5)),
                SkillCompetencies.parse("Java (2 years), Spring Boot (4 years), Git, C++ (<1 year)"));
    }
}
