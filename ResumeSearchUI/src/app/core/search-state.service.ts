import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';
import { ResumeApiService } from './api.service';
import { CandidateDetailResponse, QueryPersonaMatch } from './models';

/**
 * Holds search query/results state at the app root, not the component - so navigating away to
 * another page (e.g. Upload) and back to Search doesn't lose the last search's results, since the
 * routed Search component gets destroyed/recreated on navigation but this singleton service doesn't.
 */
@Injectable({ providedIn: 'root' })
export class SearchStateService {
  readonly queryText = signal('');
  readonly loading = signal(false);
  readonly results = signal<QueryPersonaMatch[] | null>(null);
  readonly errorMessage = signal<string | null>(null);

  /** Which result row's detail panel is currently expanded, if any. */
  readonly expandedId = signal<string | null>(null);
  readonly detailLoadingId = signal<string | null>(null);
  readonly detailError = signal<string | null>(null);
  private readonly detailCache = signal<Record<string, CandidateDetailResponse>>({});

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
      this.results.set(response.result);
    } catch (err) {
      console.error('Search failed', err);
      // Prefer the backend's own explanation (e.g. LLM misconfigured) when it sent one.
      const backendMessage = err instanceof HttpErrorResponse ? err.error?.message : undefined;
      this.errorMessage.set(backendMessage ?? 'Search failed - is the backend running at localhost:8000?');
      this.results.set(null);
    } finally {
      this.loading.set(false);
    }
  }

  detailFor(uniquefileId: string): CandidateDetailResponse | undefined {
    return this.detailCache()[uniquefileId];
  }

  async toggleDetail(uniquefileId: string): Promise<void> {
    if (this.expandedId() === uniquefileId) {
      this.expandedId.set(null);
      return;
    }
    this.expandedId.set(uniquefileId);
    this.detailError.set(null);
    if (this.detailCache()[uniquefileId]) {
      return; // already fetched this session - no need to hit the backend again
    }
    this.detailLoadingId.set(uniquefileId);
    try {
      const detail = await this.api.getCandidateDetail(uniquefileId);
      this.detailCache.update((cache) => ({ ...cache, [uniquefileId]: detail }));
    } catch (err) {
      console.error('Failed to load candidate detail', err);
      this.detailError.set('Could not load full record - is the backend running at localhost:8000?');
    } finally {
      this.detailLoadingId.set(null);
    }
  }

  /** Re-fetches a candidate's detail after an edit is saved, so the expanded panel reflects the update. */
  async reloadDetail(uniquefileId: string): Promise<void> {
    try {
      const detail = await this.api.getCandidateDetail(uniquefileId);
      this.detailCache.update((cache) => ({ ...cache, [uniquefileId]: detail }));
    } catch (err) {
      console.error('Failed to reload candidate detail', err);
    }
  }
}
