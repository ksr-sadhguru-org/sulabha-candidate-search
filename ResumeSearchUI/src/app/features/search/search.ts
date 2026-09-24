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

  /** One entry per area of expertise that matched (e.g. "senior java developer"), strongest first. */
  scoreEntries(match: QueryPersonaMatch): [string, number][] {
    return Object.entries(match.scores)
      .filter((entry): entry is [string, number] => entry[1] != null)
      .sort(([, a], [, b]) => b - a);
  }

  formatScores(entries: [string, number][]): string {
    return entries.map(([name, score]) => `${name} (score: ${score})`).join(', ');
  }

  /** "Filter Match" badge text. All-matched vs none-matched is told apart by color (green/red) and the
   *  ✓/✗ details; a partial match also shows its count, so a near-miss is clear at a glance. */
  filterBadgeLabel(match: QueryPersonaMatch): string {
    const total = match.filter_status.length;
    const matchedCount = match.filter_status.filter((f) => f.matched).length;
    return matchedCount === total || matchedCount === 0 ? 'Filter Match' : `Filter Match (${matchedCount}/${total})`;
  }

  filterBadgeClass(match: QueryPersonaMatch): string {
    const total = match.filter_status.length;
    const matchedCount = match.filter_status.filter((f) => f.matched).length;
    if (matchedCount === total) {
      return 'badge-match';
    }
    return matchedCount === 0 ? 'badge-nomatch' : 'badge-partial';
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
