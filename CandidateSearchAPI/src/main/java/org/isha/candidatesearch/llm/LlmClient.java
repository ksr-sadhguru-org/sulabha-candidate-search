package org.isha.candidatesearch.llm;

import org.isha.candidatesearch.dto.ApplicantDetails;
import org.isha.candidatesearch.dto.ParsedQuery;
import org.isha.candidatesearch.dto.Profile;

/** The three LLM calls the app makes. Each returns null when the call fails. */
public interface LlmClient {

    /** @param verifiedSkills the reviewed skill competencies field ("Java (2 years), ..."), or blank */
    Profile buildProfile(String resumeText, String verifiedSkills);

    ParsedQuery parseQuery(String query);

    /** Best-effort form suggestions from resume text alone - a human reviews them before saving. */
    ApplicantDetails suggestApplicantDetails(String resumeText);
}
