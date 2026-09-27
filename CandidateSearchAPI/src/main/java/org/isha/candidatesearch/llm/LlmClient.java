package org.isha.candidatesearch.llm;

import org.isha.candidatesearch.dto.ApplicantDetails;
import org.isha.candidatesearch.dto.ParsedQuery;
import org.isha.candidatesearch.dto.Profile;

import java.util.List;

/** The three LLM calls the app makes. Each returns null when the call fails. */
public interface LlmClient {

    /** @param verifiedSkills the reviewed skill competencies field ("Java (2 years), ..."), or blank
     *  @param domains the current domain list, so jobs are labelled consistently (roles are fixed: Roles.ALL) */
    Profile buildProfile(String resumeText, String verifiedSkills, List<String> domains);

    /** @param domains the current domain list - the query's domain is chosen from it */
    ParsedQuery parseQuery(String query, List<String> domains);

    /** Best-effort form suggestions from resume text alone - a human reviews them before saving. */
    ApplicantDetails suggestApplicantDetails(String resumeText);
}
