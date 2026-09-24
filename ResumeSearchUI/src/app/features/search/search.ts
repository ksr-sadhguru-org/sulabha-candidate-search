import { Component } from '@angular/core';
import { SearchStateService } from '../../core/search-state.service';
import { EMPTY_APPLICANT_DETAILS, QueryPersonaMatch } from '../../core/models';
import { CandidateRecordEditor } from '../../shared/candidate-record-editor/candidate-record-editor';

// Human-readable labels for FilterFieldStatus.field (camelCase, matching the backend's field names).
const FILTER_FIELD_LABELS: Record<string, string> = {
  experience: 'Experience',
  qualification: 'Qualification',
  nationality: 'Nationality',
  jobLocation: 'Location',
  languages: 'Languages',
  gender: 'Gender',
  maritalStatus: 'Marital status',
  noticePeriod: 'Notice period',
  salaryExpected: 'Salary expected',
  stayInAshram: 'Stay in ashram',
  durationWithIsha: 'Duration with Isha',
  doneIshaProgram: 'Done Isha program',
  isMeditator: 'Meditator',
  anyKindJob: 'Any kind of job',
};

@Component({
  selector: 'app-search',
  imports: [CandidateRecordEditor],
  templateUrl: './search.html',
  styleUrl: './search.css',
})
export class Search {
  readonly emptyApplicantDetails = EMPTY_APPLICANT_DETAILS;

  constructor(readonly state: SearchStateService) {}

  onQueryInput(event: Event): void {
    this.state.setQueryText((event.target as HTMLTextAreaElement).value);
  }

  /** Optional (role/title) attributes are pivoted alongside required skills so they can be scored when
   *  present, but a candidate without one gets a null score for it - filtered out here rather than
   *  showing an empty "x_score:" chip. */
  scoreEntries(match: QueryPersonaMatch): [string, number][] {
    return Object.entries(match.scores)
      .filter((entry): entry is [string, number] => entry[1] != null)
      .sort(([a], [b]) => a.localeCompare(b));
  }

  /** "Matches filters" badge text: distinguishes all-matched, some-matched and none-matched, rather than
   *  one combined yes/no - so it's clear at a glance whether a candidate misses one filter or all of them. */
  filterBadgeLabel(match: QueryPersonaMatch): string {
    const total = match.filter_status.length;
    if (total === 0) {
      return match.matches_application_filters ? 'Matches filters' : 'Skills only';
    }
    const matchedCount = match.filter_status.filter((f) => f.matched).length;
    if (matchedCount === total) {
      return 'Matches all filters';
    }
    if (matchedCount === 0) {
      return "Doesn't match filters";
    }
    return `Partially matches filters (${matchedCount}/${total})`;
  }

  filterBadgeClass(match: QueryPersonaMatch): string {
    const total = match.filter_status.length;
    if (total === 0) {
      return match.matches_application_filters ? 'badge-match' : 'badge-partial';
    }
    const matchedCount = match.filter_status.filter((f) => f.matched).length;
    return matchedCount === total ? 'badge-match' : 'badge-partial';
  }

  /** Per-field breakdown shown directly in the badge text (not just on hover): which requested filters
   *  this candidate does and doesn't satisfy, and what their own value is - mirrors how the keyword badge
   *  already shows which keywords matched inline rather than hiding them behind a tooltip. */
  filterBadgeDetail(match: QueryPersonaMatch): string {
    return match.filter_status
      .map((f) => {
        const label = FILTER_FIELD_LABELS[f.field] ?? f.field;
        const have = f.candidate_value ?? 'not on file';
        if (f.matched) {
          return `✓ ${label}: ${have}`;
        }
        const wanted = f.requested_value ? ` (wanted ${f.requested_value})` : '';
        return `✗ ${label}: ${have}${wanted}`;
      })
      .join(', ');
  }

  onSearch(): void {
    void this.state.search();
  }

  /** Ctrl+Enter in the query textarea - textareas swallow plain Enter as a newline, so it never reaches the button. */
  onSearchShortcut(): void {
    if (!this.state.loading() && this.state.queryText().trim()) {
      this.onSearch();
    }
  }

  onToggleDetail(uniquefileId: string): void {
    void this.state.toggleDetail(uniquefileId);
  }

  onDetailSaved(uniquefileId: string): void {
    void this.state.reloadDetail(uniquefileId);
  }
}
