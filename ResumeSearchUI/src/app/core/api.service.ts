import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import {
  ApplicantDetails,
  CandidateDetailResponse,
  ClearAllDataResponse,
  CreateAllTablesResponse,
  DropAllTablesResponse,
  ExtractedTextResponse,
  QueryResult,
  ResumeUploadResponse,
} from './models';

const API_BASE = 'http://localhost:8000';

@Injectable({ providedIn: 'root' })
export class ResumeApiService {
  constructor(private readonly http: HttpClient) {}

  extractText(file: File): Promise<ExtractedTextResponse> {
    const form = new FormData();
    form.append('file', file);
    return firstValueFrom(this.http.post<ExtractedTextResponse>(`${API_BASE}/extract_resume_text`, form));
  }

  uploadResume(
    uniquefileId: string,
    resumeName: string,
    resumeText: string,
    applicantDetails: ApplicantDetails,
  ): Promise<ResumeUploadResponse> {
    return firstValueFrom(
      this.http.post<ResumeUploadResponse>(`${API_BASE}/upload_resume`, {
        uniquefile_id: uniquefileId,
        resume_name: resumeName,
        resume_text: resumeText,
        applicant_details: applicantDetails,
      }),
    );
  }

  suggestApplicantDetails(resumeText: string): Promise<ApplicantDetails> {
    return firstValueFrom(
      this.http.post<ApplicantDetails>(`${API_BASE}/suggest_applicant_details`, { resume_text: resumeText }),
    );
  }

  search(query: string): Promise<QueryResult> {
    return firstValueFrom(this.http.post<QueryResult>(`${API_BASE}/query`, { query }));
  }

  dropAllTables(): Promise<DropAllTablesResponse> {
    return firstValueFrom(this.http.delete<DropAllTablesResponse>(`${API_BASE}/drop_all_tables`));
  }

  createAllTables(): Promise<CreateAllTablesResponse> {
    return firstValueFrom(this.http.post<CreateAllTablesResponse>(`${API_BASE}/create_all_tables`, {}));
  }

  clearAllData(): Promise<ClearAllDataResponse> {
    return firstValueFrom(this.http.delete<ClearAllDataResponse>(`${API_BASE}/clear_all_data`));
  }

  getCandidateDetail(uniquefileId: string): Promise<CandidateDetailResponse> {
    return firstValueFrom(this.http.get<CandidateDetailResponse>(`${API_BASE}/candidates/${uniquefileId}`));
  }
}
