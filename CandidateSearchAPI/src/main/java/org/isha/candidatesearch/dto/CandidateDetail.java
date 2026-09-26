package org.isha.candidatesearch.dto;

import java.util.List;

/** One stored candidate: resume, form fields and the search profile built from them. */
public record CandidateDetail(String candidateId, String resumeName, String resumeText, Double totalYears,
                              ApplicantDetails applicantDetails, List<ExpertiseView> expertise) {

    /** source: "resume" (LLM profile) or "form" (the reviewed skill competencies field). */
    public record ExpertiseView(String name, String kind, String field, String source, int score, Double years, List<String> terms) {
    }
}
