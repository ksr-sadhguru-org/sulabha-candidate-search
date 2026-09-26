import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  ApplicantDetails,
  CandidateDetail,
  ExtractedTextResponse,
  SearchResponse,
  TablesResponse,
  UploadResponse,
} from './models';

export const API_BASE = 'http://localhost:8000';
export const PAGE_SIZE = 20;

@Injectable({ providedIn: 'root' })
export class ResumeApiService {
  constructor(private readonly http: HttpClient) {}

  extractText(file: File): Promise<ExtractedTextResponse> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(this.http.post<ExtractedTextResponse>(`${API_BASE}/extract_resume_text`, form));
  }

  uploadResume(candidateId: string, resumeName: string, resumeText: string, applicantDetails: ApplicantDetails): Promise<UploadResponse> {
    return firstValueFrom(
      this.http.post<UploadResponse>(`${API_BASE}/upload_resume`, {
        candidate_id: candidateId,
        resume_name: resumeName,
        resume_text: resumeText,
        applicant_details: applicantDetails,
      }),
    );
  }

  suggestApplicantDetails(resumeText: string): Promise<ApplicantDetails> {
    return firstValueFrom(this.http.post<ApplicantDetails>(`${API_BASE}/suggest_applicant_details`, { resume_text: resumeText }));
  }

  /** One page of ranked results, starting at offset. */
  search(query: string, offset = 0, limit = PAGE_SIZE): Promise<SearchResponse> {
    return firstValueFrom(this.http.post<SearchResponse>(`${API_BASE}/query`, { query, offset, limit }));
  }

  getCandidateDetail(candidateId: string): Promise<CandidateDetail> {
    return firstValueFrom(this.http.get<CandidateDetail>(`${API_BASE}/candidates/${candidateId}`));
  }

  dropAllTables(): Promise<TablesResponse> {
    return firstValueFrom(this.http.delete<TablesResponse>(`${API_BASE}/drop_all_tables`));
  }

  createAllTables(): Promise<TablesResponse> {
    return firstValueFrom(this.http.post<TablesResponse>(`${API_BASE}/create_all_tables`, {}));
  }

  clearQueryCache(): Promise<{ message: string; cleared_queries: number }> {
    return firstValueFrom(this.http.delete<{ message: string; cleared_queries: number }>(`${API_BASE}/query_cache`));
  }

  clearAllData(): Promise<TablesResponse> {
    return firstValueFrom(this.http.delete<TablesResponse>(`${API_BASE}/clear_all_data`));
  }
}

/** The backend's own explanation of an error ({"message": ...}), when it sent one. */
export function backendMessage(err: unknown): string | undefined {
  return (err as { error?: { message?: string } })?.error?.message;
}
