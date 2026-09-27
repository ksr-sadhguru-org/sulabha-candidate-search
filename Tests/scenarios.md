# Sulabha — Test Scenarios

Test resumes: `Tests/Candidate Resumes for Tests/`.
Expected results, checkable by a script, are in `expected-results.json`.

## Decisions

| Topic                            | Decision                                                                                                                                                                                                                                                                                                               |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Updated resume, same person (B2) | Update the existing candidate (matched by email or phone; name as a secondary check)                                                                                                                                                                                                                                   |
| Filter-only query (E5)           | List everyone matching the filters                                                                                                                                                                                                                                                                                     |
| Spoken language (E6)             | LLM decides from context: "French translator" -> skill; "accountant who speaks Tamil" -> filter. A resume showing work in a language gets an entry for it                                                                                                                                                              |
| Location                         | LLM resolves a city to "City, State, Country" (e.g. "Coimbatore, Tamil Nadu, India"), in the form pre-fill and in query filters                                                                                                                                                                                        |
| Adjacent jobs                    | Jobs next to each other in the same field (electrician / electrical engineer, software developer / QA engineer, music teacher / vocalist) appear for each other as related matches, ranked below direct matches. Jobs from another field never do (music teacher / maths teacher, software developer / land developer) |
| Adjacent examples confirmed      | An accountant without Tally may appear for a Tally search - only as a related match, ranked lower. Teacher, tutor, instructor and trainer are equivalent words for a teaching role                                                                                                                                     |
| Roles and domains                | Every job gets a role (fixed list: teacher, performer, engineer / developer, tradesperson, ...) and a domain (growing list: music, software & it, plumbing, ...). Role + domain asked: both match directly, other roles in the domain follow as related, other domains are left out. Role only: that role in any domain. Domain only: that domain in any role. Skills count wherever they appear |
| Partial matches                  | Candidates meeting only some must-haves appear below those meeting all (e.g. Java-only for "java and python developer"). When skills are named, the job word ("developer") is context, not a must-have |
| Tiers                            | Ranked by skills met, then the job (direct > same domain > none): for "java and python developer" both skills > one skill > neither (a .NET developer) |
| Skill competencies               | Form field lists years per skill: `Java (2 years), Spring Boot (4 years)`. Human-editable; search uses these years                                                                                                                                                                                                     |
| Skill with no years              | Defaults to the candidate's overall years (filled by the LLM, or at save for skills added by hand)                                                                                                                                                                                                                     |
| Years in a query                 | Tied to a skill/profession ("python developer with 8+ years") -> that skill's years. Years alone ("10+ years experience") -> overall experience field                                                                                                                                                                  |

## A. Reading a resume (upload)

| ID  | Scenario                        | Example                                      | Expected                                                     |
| --- | ------------------------------- | -------------------------------------------- | ------------------------------------------------------------ |
| A1  | Career progression, same role   | Java Dev 2018-20 -> Senior Java Dev 2020-now | One entry "Senior Java Developer", combined years            |
| A2  | Two professions                 | Tabla player + music teacher                 | Two separate entries; neither swallows the other             |
| A3  | Career change                   | 8 yrs plumbing, then 2 yrs Java              | Java counts 2 yrs; plumbing stays its own entry              |
| A4  | Same skill, different seniority | Java: 1 / 5 / 10 yrs                         | Scores clearly ordered by experience                         |
| A5  | Skill used but not a job title  | Accountant who uses Python for reports       | Python found, scored on its actual use                       |
| A6  | Fresher, education only         | B.E. CSE, internship, no job                 | Low experience scores; education entry present               |
| A7  | Typos in the resume             | "Tabla Playr", "Woodworkng"                  | Corrected names; findable by the correct spelling            |
| A8  | Very sparse resume              | "Driver, 10 years, Chennai"                  | Still produces a usable entry                                |
| A9  | Old, unused skill               | COBOL 2005-2010, nothing since               | Lower score than recent use                                  |
| A10 | Unreadable file                 | Scanned image PDF                            | Clear error, no half-saved record                            |
| A11 | Skill competencies with years   | Plumber resume                               | `Plumbing (8 years), Pipe Fitting (8 years), ...` pre-filled |
| A12 | Location pre-fill               | Resume says "Coimbatore"                     | Form shows "Coimbatore, Tamil Nadu, India"                   |
| A13 | Language as work                | French translator                            | "French" entry (skill), not just a spoken-language field     |
| A14 | Job labels                   | Music teacher, tabla player, electrician, QA, land developer | teacher + music; performer + music; tradesperson + electrical; tester / qa + software & it; engineer / developer + construction & real estate |

## B. Duplicates

| ID  | Scenario                                    | Expected                                                                  |
| --- | ------------------------------------------- | ------------------------------------------------------------------------- |
| B1  | Same resume, different file name            | Flagged "Already exists"                                                  |
| B2  | Same person, updated resume (new job added) | Existing candidate updated; search reflects the new job; no second record |
| B3  | Two different people, same name             | Two separate candidates                                                   |

## C. Matching (who gets found)

| ID  | Scenario                          | Example query                                        | Expected                                                                                                                                                                               |
| --- | --------------------------------- | ---------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| C1  | General term covers specific ones | "teacher"                                            | All teachers (music, sanskrit, montessori...)                                                                                                                                          |
| C2  | Specific term stays specific      | "music teacher"                                      | Music teacher first; other teachers lower or absent                                                                                                                                    |
| C3  | Equivalent wording                | "tutor", "instructor"                                | Finds teachers / instructors                                                                                                                                                           |
| C4  | Must-have missing                 | "c++ programmer" (nobody has C++)                    | No skill matches                                                                                                                                                                       |
| C5  | Two must-haves                    | "java and python developer"                          | People with both first; people with only one follow as partial matches; nobody from another field (no land developer)                                                                  |
| C6  | Seniority as nice-to-have         | "senior carpenter"                                   | All carpenters; seniors higher; non-carpenters never skill matches                                                                                                                     |
| C7  | Skill hidden inside a role        | "spring boot"                                        | Finds the Java developer                                                                                                                                                               |
| C8  | Word forms                        | "plumber" / "plumbing", "carpentry"                  | Same results                                                                                                                                                                           |
| C9  | Typos in the query                | "electrcian", "sanskirt teacher"                     | Same as correct spelling                                                                                                                                                               |
| C10 | Unrelated word coincidence        | "senior" / "sr", "language"                          | No false matches (classroom, "Languages:")                                                                                                                                             |
| C11 | Abbreviations                     | "sr java dev", "B.E."                                | Same as the full form                                                                                                                                                                  |
| C12 | Adjacent professions              | "electrician" vs "electrical engineer"               | Its own profession first; the other only as a related match below                                                                                                                      |
| C13 | Gibberish                         | "sdfsdf dddid"                                       | "We couldn't understand this search. Try describing the role, skills or location, e.g. 'Java developer in Coimbatore'."                                                                |
| C14 | Nothing specific                  | "looking for someone good"                           | Hint to add a role, skill or location                                                                                                                                                  |
| C15 | Understood, no matches            | "astronaut in Coimbatore"                            | "No candidates match 'astronaut' in Coimbatore."                                                                                                                                       |
| C17 | Core skill of a job               | "python developer" vs "candidate with python skills" | Both find everyone who knows Python; for "python developer" the developers come first and Kavya (accountant who uses Python) follows as a related match                                |
| C18 | Same job, other wording | "music instructor" | The music teacher, and the tabla player as a same-field related match; never the maths tutor or other teachers |
| C19 | Same domain, no skill         | "Java developer in india with 8+ years experience"   | Java developers first; other software developers (Python, .NET) follow as partial matches (the job without Java) |
| C20 | Role or domain alone          | "trades", "music" | "trades" = the tradesperson role in any domain (plumbers, carpenters, electricians - not electrical engineers); "music" = the music domain in any role |
| C21 | Skills listed before a job | "python, java developer in mumbai" vs "java developer in mumbai" | Both read java as a skill: Deepa (both skills) first, then one-skill people with the Mumbai Java developer ahead; "java developer in mumbai" puts him first |
| C22 | Construction and bare "developer" | "land developer", "builder", "construction", "developer" | The land developer is found by each; a bare "developer" is open - software developers and the land developer |
| C16 | Broad job category                | "software engineer", "software developer"            | Every programming role (Java, Python, .NET, full stack, etc.); adjacent QA / former programmers as related, ranked below; never electrical engineers, electricians or a land developer |

## D. Ranking

| ID  | Scenario                              | Expected                                                      |
| --- | ------------------------------------- | ------------------------------------------------------------- |
| D1  | More relevant experience ranks higher | 9-yr Java dev above 6-yr above 2-yr                           |
| D2  | Profile match above text match        | A resume that only mentions the word ranks below real matches |
| D3  | Related-word matches rank lowest      | Teal matches at the bottom                                    |
| D4  | Ties broken by relevant years         | Same score, more years first                                  |
| D5  | Same search, same results             | Repeating a query gives an identical order                    |

## E. Filters

| ID  | Scenario                       | Example                                              | Expected                                            |
| --- | ------------------------------ | ---------------------------------------------------- | --------------------------------------------------- |
| E1  | Location                       | "... in Coimbatore"                                  | Match / no match per candidate; matches first       |
| E2  | Experience with a skill        | "python developer with 8+ years"                     | Python years >= 8 passes, else fails                |
| E3  | Manual-only fields             | meditator / stay in ashram                           | Only hand-entered values count; "not on file" shown |
| E4  | Several filters, partial match | location + meditator + ashram                        | Tiered: 3/3 > 2/3 > 1/3                             |
| E5  | Filter-only query              | "anyone in Coimbatore who is a meditator"            | Everyone matching the filters                       |
| E6  | Spoken language                | "accountant who speaks Tamil" vs "French translator" | Tamil = filter; French = skill                      |
| E7  | City in query                  | "... in Coimbatore"                                  | Resolved to "Coimbatore, Tamil Nadu, India"         |
| E8  | State-level search             | "... in Tamil Nadu"                                  | Everyone in any Tamil Nadu city                     |
| E9  | Country-level search           | "... in India"                                       | Everyone in India                                   |
| E10 | City with no candidates        | "... in Indore"                                      | Candidates still listed, location shown as no match |
| E11 | Years alone                    | "someone with 10+ years experience"                  | Uses the overall experience field                   |

## F. Robustness and scale

| ID  | Scenario                                           | Expected                        |
| --- | -------------------------------------------------- | ------------------------------- |
| F1  | LLM unavailable                                    | Clear error, nothing half-saved |
| F2  | Bulk upload of ~20 files                           | All saved, none skipped         |
| F3  | ~500 resumes                                       | Search stays under ~3 s         |
| F4  | Special characters: "C++", "C#", ".NET", "Node.js" | Found correctly, nothing breaks |
| F5  | Paging ("Load more")                              | Pages of results join up to exactly the full ranked list, nothing missing or repeated |

## G. Real HR queries

| ID  | Query                                                                                 | Expected                                                                                                              |
| --- | ------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| G1  | senior developer with java skills in indore                                           | All Java developers; seniors higher; location no match for all (E10)                                                  |
| G2  | Person who knows Tally software and is in Pune                                        | Tally users; Pune first; accountants without Tally only as related, ranked lower                                      |
| G3  | Candidate who knows Java and is in Pune                                               | Anyone with Java (developer, tester, trainer); Pune first                                                             |
| G4  | Candidate who is an electrical engineer and is in coimbatore and is a meditator       | Electrical engineers first, tiered by location + meditator; electricians only as related, below                       |
| G5  | candidate who knows sanskrit and is a teacher                                         | Sanskrit teacher first; Sanskrit counts as a skill here                                                               |
| G6  | Music teacher                                                                         | Music teachers first (C2); other music roles may follow as related; never other teachers                              |
| G7  | Plumber                                                                               | Plumbers, including "plumbing" resumes (C8)                                                                           |
| G8  | Electrician                                                                           | Electricians first; electrical engineers only as related, below (C12)                                                 |
| G9  | teachers                                                                              | Same as "teacher" (C1)                                                                                                |
| G10 | Candidate who is willing to stay in ashram                                            | Everyone with Stay in ashram = Yes (E5)                                                                               |
| G11 | python developer with 8+ years experience in Coimbatore who is also an Isha meditator | Python >= 8 yrs passes (E2); location and meditator as filters; "Isha meditator" is the meditator filter, not a skill |
