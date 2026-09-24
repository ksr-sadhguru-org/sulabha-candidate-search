package org.isha.resumesearch.llm;

import org.isha.resumesearch.dto.ApplicantDetails;
import org.isha.resumesearch.dto.ApplicationFilterResponse;
import org.isha.resumesearch.dto.QueryParseResponse;
import org.isha.resumesearch.dto.ResumeParseResponse;

/** Turns free text (a resume, or an HR search query) into structured, queryable data. */
public interface LlmExtractor {

    ResumeParseResponse extractFromResume(String resumeText);

    QueryParseResponse extractFromQuery(String query);

    ApplicationFilterResponse extractApplicationFilters(String query);

    /** Best-effort applicant-detail suggestions from resume text alone - a human must review before saving. */
    ApplicantDetails suggestApplicantDetails(String resumeText);
}
