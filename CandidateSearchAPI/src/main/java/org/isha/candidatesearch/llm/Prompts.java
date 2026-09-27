package org.isha.candidatesearch.llm;

/** Prompt templates. Filled with String.formatted, so they must not contain a literal percent sign. */
final class Prompts {

    private Prompts() {
    }

    static final String PROFILE = """
            Build a search profile of this candidate from their resume. Correct spelling mistakes.

            One entry per area of expertise:
            - name: e.g. "Senior Java Developer". Merge a career progression into ONE entry named by the latest title,
              with combined years. Different professions stay separate entries (e.g. "Tabla Player" and "Music Teacher").
            - kind: "profession" (a job or trade), "skill" (a skill or tool not part of a profession entry),
              "education" (one entry, highest level, e.g. "Bachelors"), or "language" (a language the candidate works in,
              e.g. a translator's "French" - never a language they merely speak).
            - role and domain: for a profession entry only (null for skills and education). role is the kind of work,
              exactly one of: %s. domain is the subject or area, from: %s - only when none fits, a new short domain name.
              E.g. music teacher = teacher + music; tabla player = performer + music; maths tutor = teacher + mathematics &
              science; electrician = tradesperson + electrical; electrical engineer = engineer / developer + electrical;
              java developer = engineer / developer + software & it; qa engineer = tester / qa + software & it; java trainer
              = teacher + software & it; land developer or builder = engineer / developer + construction & real estate;
              plumber =
              tradesperson + plumbing; yoga instructor = teacher + yoga & wellness; intern = trainee.
            - terms: lowercase words and phrases an HR search might use that this entry proves: the name, synonyms,
              abbreviations, the plain profession ("java developer" for "Senior Java Developer"), both trade forms
              ("plumber" and "plumbing"), earlier titles, and the skills and tools used ("java", "spring boot"), with a
              tool's base name too ("tally" for "TallyPrime"). Never a bare generic word ("developer", "engineer", "teacher")
              and never a different profession (an electrical engineer is not an "electrician").
            - years: years of experience relevant to this entry only. Unrelated work counts 0, closely related work half,
              academic use 0.5. Don't double-count overlapping jobs.
            - lastUsedYear: the last year this was used (the current year if ongoing).
            - score: education only, by level: PhD 97, Masters 90, Bachelors 80, Diploma 70, High School 60. Else null.
            - totalYears: the candidate's total years of work experience.

            HR-verified skills with years - use these years where they apply: %s

            Return only this JSON:
            {"totalYears": 9, "entries": [
              {"name": "Senior Java Developer", "kind": "profession", "role": "engineer / developer", "domain": "software & it", "years": 9, "lastUsedYear": 2026, "score": null,
               "terms": ["senior java developer", "sr java developer", "java developer", "java", "spring boot", "microservices"]},
              {"name": "Python", "kind": "skill", "role": null, "domain": null, "years": 2, "lastUsedYear": 2024, "score": null, "terms": ["python"]},
              {"name": "Bachelors", "kind": "education", "role": null, "domain": null, "years": 0, "lastUsedYear": null, "score": 80,
               "terms": ["bachelors", "b.e.", "bachelor of engineering", "computer engineering"]}
            ]}

            Resume text: "%s"
            """;

    static final String QUERY = """
            Turn this HR search query into search criteria. Correct spelling mistakes.

            - understood: false if the query is gibberish or not a search for people, else true. A real job, even one
              nobody may have ("astronaut in Coimbatore"), is understood.
            - role and domain of the job asked for, each only from its list, null when the query doesn't say:
              role, one of: %s. domain, from: %s.
              E.g. "music instructor" = teacher + music; "trades" = tradesperson; "music" = domain music; "teacher" =
              teacher; "electrician" = tradesperson + electrical; "java developer" = engineer / developer + software & it;
              "accountant" = professional + accounting & finance; "builder", "land developer" = engineer / developer +
              construction & real estate. A bare job word with nothing else pointing to a domain leaves it null:
              "developer", "senior developer" = engineer / developer, domain null; but "developer with java skills" =
              engineer / developer + software & it. An unlisted job ("astronaut") leaves both null.
            - job: the job asked for as worded, or null when none is asked:
              term: lowercase, singular ("music instructor"). alternatives: other words for the same job a resume might use
              ("music teacher", "music tutor"), keeping the subject words - never a bare "teacher" or "developer".
              minYears: years of experience tied to the job ("electrician with 5+ years" -> 5), else null.
            - must: the specific skills, tools or languages asked for, each one term - including one named in the job title
              ("java developer" -> "java"; "java and python developer" and "python, java developer" -> "java", "python";
              "knows tally" -> "tally";
              "sanskrit teacher" -> "sanskrit"). Not the job itself. Years asked with the job belong to its skill
              ("java developer with 8+ years" -> "java", minYears 8).
              term: lowercase, as a resume would write it ("tally", not "tally software"). alternatives: equivalents and
              product variants ("tally" -> "tallyprime", "tally erp 9"). minYears: years tied to this skill, else null.
            - nice: extras that only improve ranking: seniority ("senior", "lead"), and education when a profession or
              skill is also asked. Education asked alone goes in must.
            - filters, null when not mentioned:
              location: {"city", "state", "country"}. Resolve a city to its state and country ("coimbatore" -> "Coimbatore",
              "Tamil Nadu", "India"); a state or country alone leaves the smaller parts null. Use common English names
              ("Bengaluru", "Mysuru").
              languages: languages a candidate must speak ("speaks Tamil", "Tamil speaking"). A language asked as a skill
              or as the work itself ("knows Sanskrit", "French translator") is a must term instead.
              minTotalYears: years of experience not tied to any need ("someone with 10+ years experience").
              nationality, gender, maritalStatus, noticePeriod, maxSalary (a number).
              stayInAshram, doneIshaProgram, isMeditator ("Isha meditator" counts), anyKindJob: "Yes" or "No".
              durationWithIsha: e.g. "3 ~ 4 Years".
            - A vague query with nothing specific ("someone good") returns null role, domain and job, and empty must,
              nice and filters.

            Return only this JSON:
            {"understood": true,
             "role": "engineer / developer", "domain": "software & it",
             "job": {"term": "python developer", "alternatives": ["python programmer", "python engineer"], "minYears": null},
             "must": [{"term": "python", "alternatives": ["python programming"], "minYears": 8}],
             "nice": [{"term": "senior", "alternatives": ["sr", "lead"], "minYears": null}],
             "filters": {"location": {"city": "Coimbatore", "state": "Tamil Nadu", "country": "India"}, "languages": ["Tamil"],
               "minTotalYears": null, "nationality": null, "gender": null, "maritalStatus": null, "noticePeriod": null,
               "maxSalary": null, "stayInAshram": null, "doneIshaProgram": null, "isMeditator": "Yes", "anyKindJob": null,
               "durationWithIsha": null}}

            HR query: "%s"
            """;

    static final String SUGGEST = """
            Suggest values for an HR application form from this resume. A human reviews them, but never guess or invent
            a value the text does not support.

            Fill only these, and only when the text supports it:
            - name; emailFrom (email); phone (as written)
            - dob, gender ("Male"/"Female"), maritalStatus ("Single"/"Married"/"Divorced"): ONLY from an explicit labelled
              line (e.g. "Gender: Male"), never inferred from a name
            - qualification: highest education (e.g. "Bachelor's", "Master's", "PhD")
            - experience: total years, as exactly one of "0 ~ 3years", "4 ~ 6years", "7 ~ 10years", "10+years"
            - linkedinProfile; nationality (only if stated)
            - jobLocation: current location as "City, State, Country", resolving a city to its state and country
              (e.g. "Coimbatore" -> "Coimbatore, Tamil Nadu, India"). Use common English names ("Bengaluru").
            - languages: spoken languages, comma separated
            - otherInterests: hobbies section, if present
            - skillCompetencies: the top skills with the years each was used, as "Skill (N years)", comma separated,
              e.g. "Java (2 years), Spring Boot (4 years), Git (1 years)". Count only time the skill was actually used;
              when the resume gives no basis for a skill's years, use the candidate's total years.

            Always null (never in a resume): address, reasonForChange, noticePeriod, salaryExpected, stayInAshram,
            anyKindJob, durationWithIsha, doneIshaProgram, jobId, applicantPrograms, isMeditator

            Return only this JSON (all keys):
            {"name": null, "emailFrom": null, "phone": null, "dob": null, "gender": null, "maritalStatus": null,
             "address": null, "qualification": null, "experience": null, "skillCompetencies": null, "otherInterests": null,
             "reasonForChange": null, "noticePeriod": null, "salaryExpected": null, "stayInAshram": null, "anyKindJob": null,
             "durationWithIsha": null, "doneIshaProgram": null, "linkedinProfile": null, "jobId": null, "nationality": null,
             "jobLocation": null, "languages": null, "applicantPrograms": null, "isMeditator": null}

            Resume text: "%s"
            """;
}
