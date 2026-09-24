package org.isha.resumesearch.llm;

/** Prompt templates - ported verbatim from the original Python service's prompt.py. */
final class Prompts {

    private Prompts() {
    }

    static final String RESUME_PARSE = """
            Extract the candidate's areas of expertise from this resume. Correct any spelling mistakes.

            WHAT TO RETURN - one entry per area of expertise, each with a canonical name and synonyms (other names
            for the same thing only, e.g. "Sr. Java Developer" - never skills or earlier titles, those go in includes):
            - Merge a career progression into ONE entry, named by the most senior/current title, with combined
              years (e.g. "Java Developer" 2018-2020 + "Senior Java Developer" 2020-now -> "Senior Java Developer").
            - includes: the skills, tools and earlier job titles this entry is made up of (e.g. "Java", "Spring Boot",
              "Java Developer"), so the candidate can be found by searching any of them.
            - Skills that are not part of any role get their own entry (e.g. a Java developer who also knows Python).
            - Education: one entry for the highest level (e.g. "Masters"), with its specialization in includes.
            - type: "role" for a profession/job title or education level, "skill" for a standalone skill.

            SCORING - one score per entry, judged on that entry alone:
            - years: years of experience relevant to this entry. Unrelated work counts for nothing (8 years of
              plumbing adds 0 to a Java entry); closely related work counts half. Don't double-count overlapping jobs.
            - score (0-100), from those years: 10+ -> 93-97, 6-10 -> 85-92, 3-6 -> 75-84, 1-3 -> 65-74,
              under 1 or academic only -> 55-64, listed but no evidence of use -> 45-54.
              Then +0 to +3 for a relevant degree/certification, -5 to -10 if not used in the last 5 years.
            - Education entries are scored by level: PhD 95-100, Masters 85-94, Bachelors 75-84, Diploma 65-74,
              High School 55-64, other 45-54.

            Return only this JSON, with no other text:
            {"items": [
              {"type": "role", "canonical": "Senior Java Developer", "synonyms": ["Sr. Java Developer"], "score": 90,
               "years": 8, "includes": ["Java", "Java Developer", "Spring Boot", "REST APIs", "Microservices"]},
              {"type": "skill", "canonical": "Python", "synonyms": [], "score": 68, "years": 2, "includes": []},
              {"type": "role", "canonical": "Bachelors", "synonyms": ["B.E."], "score": 80, "years": 0,
               "includes": ["Computer Science"]}
            ]}

            Resume Text: "%s"
            """;

    static final String QUERY_PARSE = """
            Extract required skills AND, separately, job-title/seniority/education requirements from this
            HR query, with canonical terms and synonyms for each. These will be matched exactly against a
            candidate skill/role index, so extract nothing that isn't a genuine technical/soft skill or a
            job title/seniority/education level.
            There may be spelling mistakes in the query, so you need to correct them.

            DEFINITIONS:
            - skills: Technologies, tools, programming languages, and capabilities required for the position (e.g. "React", "Python", "Team Leadership")
            - roles: Job titles, seniority levels, and education requirements (e.g. "Senior Developer", "Tech Lead", "Masters")
            Put each extracted item under whichever key it belongs to - a query can mention only skills, only roles, both, or neither.
            - synonyms: spelling variants plus close equivalents a resume might use instead (e.g. "Teacher" -> "Tutor",
              "Instructor", "Educator"; "Developer" -> "Programmer", "Software Engineer"). True equivalents only - not related fields.

            EXPLICITLY EXCLUDE all of the following - they are handled by a separate filter, never put them in "skills" or "roles":
            - Years of experience (e.g. "5+ years")
            - Locations (e.g. "Coimbatore", "Chennai", "Remote")
            - Spoken/human languages (e.g. "Tamil", "English") - these are NOT programming languages
            - Any Isha-specific lifestyle attributes (meditator status, ashram residency, Isha program completion, nationality, gender, marital status, notice period, salary)

            JSON Format:
            {
              "skills": [{"canonical": "React", "synonyms": ["ReactJS", "React.js"]}],
              "roles": [{"canonical": "Senior Developer", "synonyms": ["Sr. Developer"]}]
            }

            Return only valid JSON matching the schema above, with keys "skills" and "roles" (empty arrays if none) - without any additional text or formatting.

            HR Query: "%s"
            """;

    static final String APPLICATION_FILTER = """
            Extract application-level filters from the HR query. These are filters that should be applied to the applicant's application data (not skills/roles).
            There may be spelling mistakes in the query, so you need to correct them.

            Extract filters for the following fields if mentioned in the query (use null for fields not mentioned):
            - experience: Years of experience (e.g., "5+ years", "3-5 years")
            - qualification: Education qualification (e.g., "Bachelor's", "Master's", "PhD")
            - nationality: Nationality requirement
            - jobLocation: Preferred or required job location
            - languages: Language requirements, comma separated
            - gender: Gender preference if specified
            - maritalStatus: Marital status if specified
            - noticePeriod: Notice period requirements
            - salaryExpected: Salary expectations, as free text
            - stayInAshram: Willingness to stay in ashram ("Yes"/"No"/null)
            - durationWithIsha: Duration with Isha organization
            - doneIshaProgram: Whether Isha programs completed ("Yes"/"No"/null)
            - isMeditator: Whether candidate is a meditator ("Yes"/"No"/null)
            - anyKindJob: Open to any kind of job ("Yes"/"No"/null)

            JSON Format:
            {
              "experience": "5+ years", "qualification": null, "nationality": "Indian", "jobLocation": "Bangalore",
              "languages": "Tamil, English", "gender": null, "maritalStatus": null, "noticePeriod": "Immediate",
              "salaryExpected": null, "stayInAshram": null, "durationWithIsha": null, "doneIshaProgram": null,
              "isMeditator": "Yes", "anyKindJob": null
            }

            EXTRACTION GUIDELINES:
            - Only extract filters that are explicitly or implicitly mentioned in the query; use null otherwise
            - Normalize values to standard formats (e.g., "5+" -> "5+ years")

            Return only valid JSON matching the schema above - without any additional text or formatting.

            HR Query: "%s"
            """;

    static final String APPLICANT_DETAILS_SUGGEST = """
            Suggest values for a subset of an HR application form, based ONLY on what this resume's text
            actually says. This is a best-effort suggestion that a human will review and correct, but you
            must still never guess or invent a value you have no textual basis for.

            Attempt to fill in ONLY these fields, and ONLY when the resume text actually supports it. Some
            resume formats (e.g. traditional Indian CVs) include an explicit "Personal Details" section
            stating date of birth, gender and marital status directly - extract these ONLY when the resume
            states them that explicitly (e.g. "GENDER: MALE", "MARITAL STATUS: SINGLE", a DOB line); never
            infer them from a name or any other indirect signal:
            - name: the candidate's name
            - emailFrom: an email address, if present
            - dob: date of birth, ONLY if an explicit "Date of Birth" field/line is present
            - gender: ONLY if an explicit "Gender" field/line is present - normalize to exactly "Male" or "Female"
              (title case), regardless of how it's cased in the resume (e.g. "MALE" -> "Male")
            - maritalStatus: ONLY if an explicit "Marital Status" field/line is present - normalize to exactly
              "Single", "Married" or "Divorced" (title case), regardless of casing in the resume
            - qualification: highest education qualification (e.g. "Bachelor's", "Master's", "PhD")
            - experience: total years of experience, expressed as EXACTLY one of these bucket strings
              (pick the closest one): "0 ~ 3years", "4 ~ 6years", "7 ~ 10years", "10+years"
            - linkedinProfile: a LinkedIn URL, if present
            - nationality: nationality, if explicitly stated
            - jobLocation: current city/location, if stated (e.g. in contact info)
            - languages: spoken/human languages listed (not programming languages), comma separated
            - otherInterests: hobbies/interests section, if present
            - skillCompetencies: a short comma-separated summary of top skills

            For every other field below, you MUST return null - do not guess, even if it seems plausible.
            None of these are ever stated in resume text, so any non-null value here would be a fabrication:
            address, reasonForChange, noticePeriod, salaryExpected, stayInAshram,
            anyKindJob, durationWithIsha, doneIshaProgram, jobId, applicantPrograms, isMeditator

            JSON Format (all 24 keys required, most will be null):
            {
              "name": null, "emailFrom": null, "dob": null, "gender": null, "maritalStatus": null,
              "address": null, "qualification": null, "experience": null, "skillCompetencies": null,
              "otherInterests": null, "reasonForChange": null, "noticePeriod": null, "salaryExpected": null,
              "stayInAshram": null, "anyKindJob": null, "durationWithIsha": null, "doneIshaProgram": null,
              "linkedinProfile": null, "jobId": null, "nationality": null, "jobLocation": null,
              "languages": null, "applicantPrograms": null, "isMeditator": null
            }

            Return only valid JSON matching the schema above - without any additional text or formatting.

            Resume Text: "%s"
            """;
}
