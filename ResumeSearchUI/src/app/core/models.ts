// These interfaces mirror the backend's JSON wire format (snake_case) directly, since the
// service returns snake_case (spring.jackson.property-naming-strategy: SNAKE_CASE) - no mapping layer needed.

export interface DuplicateMatch {
  uniquefile_id: string;
  resume_name: string;
}

export interface ExtractedTextResponse {
  resume_name: string;
  extracted_text: string;
  duplicate_of: DuplicateMatch | null;
}

export interface DropAllTablesResponse {
  message: string;
  dropped_tables: string[];
  warning: string;
}

export interface CreateAllTablesResponse {
  message: string;
  tables: string[];
}

export interface ClearAllDataResponse {
  message: string;
  cleared_tables: string[];
  warning: string;
}

export interface ResumeUploadResponse {
  message: string;
  status: boolean;
  uniquefile_id: string;
}

export interface ApplicantDetails {
  name: string | null;
  email_from: string | null;
  dob: string | null;
  gender: string | null;
  marital_status: string | null;
  address: string | null;
  qualification: string | null;
  experience: string | null;
  skill_competencies: string | null;
  other_interests: string | null;
  reason_for_change: string | null;
  notice_period: string | null;
  salary_expected: number | null;
  stay_in_ashram: string | null;
  any_kind_job: string | null;
  duration_with_isha: string | null;
  done_isha_program: string | null;
  linkedin_profile: string | null;
  job_id: string | null;
  nationality: string | null;
  job_location: string | null;
  languages: string | null;
  applicant_programs: string | null;
  is_meditator: string | null;
}

export const EMPTY_APPLICANT_DETAILS: ApplicantDetails = {
  name: null, email_from: null, dob: null, gender: null, marital_status: null, address: null,
  qualification: null, experience: null, skill_competencies: null, other_interests: null,
  reason_for_change: null, notice_period: null, salary_expected: null, stay_in_ashram: null,
  any_kind_job: null, duration_with_isha: null, done_isha_program: null, linkedin_profile: null,
  job_id: null, nationality: null, job_location: null, languages: null, applicant_programs: null,
  is_meditator: null,
};

/** One requested application filter's outcome for this candidate - only present for fields the query
 *  actually mentioned. candidate_value is null when the candidate has nothing on file for that field. */
export interface FilterFieldStatus {
  field: string;
  matched: boolean;
  requested_value: string | null;
  candidate_value: string | null;
}

export interface QueryPersonaMatch {
  uniquefile_id: string;
  /** Value is null for an optional (role/title) attribute the candidate doesn't actually have. */
  scores: Record<string, number | null>;
  matched_via_keyword: boolean;
  matched_keywords: string[];
  /** The LLM's equivalents of query words that matched (e.g. "instructor" for "teacher"). */
  related_keywords: string[];
  partial_keyword_match: boolean;
  matches_application_filters: boolean;
  filter_status: FilterFieldStatus[];
  name: string | null;
  experience: string | null;
  qualification: string | null;
  nationality: string | null;
  job_location: string | null;
  languages: string | null;
  gender: string | null;
  marital_status: string | null;
  notice_period: string | null;
  salary_expected: number | null;
  stay_in_ashram: string | null;
  duration_with_isha: string | null;
  done_isha_program: string | null;
  is_meditator: string | null;
  any_kind_job: string | null;
}

export interface QueryResult {
  result: QueryPersonaMatch[];
}

export interface CandidateDetailResponse {
  uniquefile_id: string;
  resume_name: string;
  resume_text: string;
  applicant_details: ApplicantDetails | null;
}
