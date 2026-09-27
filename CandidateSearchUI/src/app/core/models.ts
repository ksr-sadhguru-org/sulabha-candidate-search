// Mirrors the backend's JSON (snake_case) directly - no mapping layer.

export interface DuplicateMatch {
  candidate_id: string;
  resume_name: string;
}

export interface ExtractedTextResponse {
  resume_name: string;
  extracted_text: string;
  duplicate_of: DuplicateMatch | null;
}

export interface TablesResponse {
  message: string;
  dropped_tables?: string[];
  cleared_tables?: string[];
  tables?: string[];
}

export interface UploadResponse {
  status: boolean;
  message: string;
  candidate_id: string;
  /** True when the upload was recognized as an updated resume of a candidate already on file. */
  updated_existing: boolean;
}

export interface ApplicantDetails {
  name: string | null;
  email_from: string | null;
  phone: string | null;
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
  name: null, email_from: null, phone: null, dob: null, gender: null, marital_status: null, address: null,
  qualification: null, experience: null, skill_competencies: null, other_interests: null,
  reason_for_change: null, notice_period: null, salary_expected: null, stay_in_ashram: null,
  any_kind_job: null, duration_with_isha: null, done_isha_program: null, linkedin_profile: null,
  job_id: null, nationality: null, job_location: null, languages: null, applicant_programs: null,
  is_meditator: null,
};

export interface MatchedEntry {
  name: string;
  score: number;
  years: number | null;
  via_term: string;
  /** Found via an equivalent term (e.g. "tutor" for "teacher") or only via a degree. */
  related: boolean;
}

export type FilterStatus = 'pass' | 'fail' | 'not_on_file';

export interface FilterCheck {
  field: string;
  label: string;
  status: FilterStatus;
  requested: string | null;
  candidate_value: string | null;
}

/** profile: every must-have in the search profile; related: some via an equivalent; text: some only in the
 *  resume text; partial: only some of the must-haves met; field: none met, but a job in the same field as the
 *  direct matches; filters: a filter-only search. */
export type MatchType = 'profile' | 'related' | 'text' | 'partial' | 'field' | 'filters';

export interface CandidateMatch {
  candidate_id: string;
  name: string | null;
  match_type: MatchType;
  /** How many of the query's must-haves this candidate meets. */
  must_met: number;
  must_total: number;
  matched: MatchedEntry[];
  text_matches: string[];
  nice_matches: string[];
  filters: FilterCheck[];
  filters_passed: number;
  experience: string | null;
  total_years: number | null;
  qualification: string | null;
  job_location: string | null;
  languages: string | null;
  is_meditator: string | null;
  stay_in_ashram: string | null;
}

export interface SearchResponse {
  /** Shown when the query was unclear or matched no one. */
  message: string | null;
  results: CandidateMatch[];
  /** How many matched in all - results is one page of them. */
  total: number;
}

export interface ExpertiseView {
  name: string;
  kind: string | null;
  /** The job's field (job entries only), e.g. "software & it". */
  field: string | null;
  source: 'resume' | 'form';
  score: number;
  years: number | null;
  terms: string[];
}

export interface FieldCount {
  name: string;
  candidates: number;
}

export interface CandidateDetail {
  candidate_id: string;
  resume_name: string;
  resume_text: string;
  total_years: number | null;
  applicant_details: ApplicantDetails | null;
  expertise: ExpertiseView[];
}
