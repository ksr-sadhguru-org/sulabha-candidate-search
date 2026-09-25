import { Injectable, signal } from '@angular/core';
import { backendMessage, ResumeApiService } from './api.service';
import { CandidateDetail, CandidateMatch } from './models';

/** Search state at the app root, so leaving the Search page and coming back keeps the last results. */
@Injectable({ providedIn: 'root' })
export class SearchStateService {
  readonly queryText = signal('');
  readonly loading = signal(false);
  readonly results = signal<CandidateMatch[] | null>(null);
  /** The backend's note for an unclear or unmatched query. */
  readonly message = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);

  readonly expandedId = signal<string | null>(null);
  readonly detailLoadingId = signal<string | null>(null);
  readonly detailError = signal<string | null>(null);
  private readonly detailCache = signal<Record<string, CandidateDetail>>({});

  constructor(private readonly api: ResumeApiService) {}

  setQueryText(value: string): void {
    this.queryText.set(value);
  }

  async search(): Promise<void> {
    const query = this.queryText().trim();
    if (!query) {
      return;
    }
    this.loading.set(true);
    this.errorMessage.set(null);
    try {
      const response = await this.api.search(query);
      this.results.set(response.results);
      this.message.set(response.message);
    } catch (err) {
      console.error('Search failed', err);
      this.errorMessage.set(backendMessage(err) ?? 'Search failed - is the backend running at localhost:8000?');
      this.results.set(null);
      this.message.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  detailFor(candidateId: string): CandidateDetail | undefined {
    return this.detailCache()[candidateId];
  }

  async toggleDetail(candidateId: string): Promise<void> {
    if (this.expandedId() === candidateId) {
      this.expandedId.set(null);
      return;
    }
    this.expandedId.set(candidateId);
    this.detailError.set(null);
    if (!this.detailCache()[candidateId]) {
      this.detailLoadingId.set(candidateId);
      await this.reloadDetail(candidateId);
      this.detailLoadingId.set(null);
    }
  }

  async reloadDetail(candidateId: string): Promise<void> {
    try {
      const detail = await this.api.getCandidateDetail(candidateId);
      this.detailCache.update((cache) => ({ ...cache, [candidateId]: detail }));
    } catch (err) {
      console.error('Failed to load candidate detail', err);
      this.detailError.set(backendMessage(err) ?? 'Could not load the full record - is the backend running at localhost:8000?');
    }
  }
}
