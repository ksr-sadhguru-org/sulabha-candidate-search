import { Component } from '@angular/core';
import { SearchStateService } from '../../core/search-state.service';
import { CandidateMatch, EMPTY_APPLICANT_DETAILS, FilterCheck, MatchedEntry } from '../../core/models';
import { CandidateRecordEditor } from '../../shared/candidate-record-editor/candidate-record-editor';
import { ExpandablePanel } from '../../shared/expandable-panel/expandable-panel';

/** Shown under the Search button; clicking one puts it in the search box. */
const EXAMPLES = [
  'Java developer in Pune',
  'Music teacher who is an Isha meditator',
  'Python developer with 8+ years experience in Coimbatore',
  'Candidate who is willing to stay in ashram',
];

const STATUS_MARK: Record<FilterCheck['status'], string> = { pass: '✓', fail: '✗', not_on_file: '?' };

@Component({
  selector: 'app-search',
  imports: [CandidateRecordEditor, ExpandablePanel],
  templateUrl: './search.html',
  styleUrl: './search.css',
})
export class Search {
  readonly emptyApplicantDetails = EMPTY_APPLICANT_DETAILS;
  readonly examples = EXAMPLES;

  constructor(readonly state: SearchStateService) {}

  onQueryInput(event: Event): void {
    this.state.setQueryText((event.target as HTMLTextAreaElement).value);
  }

  /** "senior java developer (score 91, 9 yrs)" per matched area of expertise. */
  formatEntries(entries: MatchedEntry[]): string {
    return entries.map((e) => `${e.name} (score ${e.score}${e.years == null ? '' : ', ' + years(e.years)})`).join(', ');
  }

  matchLabel(match: CandidateMatch): string {
    return match.match_type === 'partial'
      ? `Partial match (${match.must_met} of ${match.must_total})`
      : { profile: 'Profile match', related: 'Related match', text: 'Resume text match', filters: '' }[match.match_type];
  }

  matchClass(match: CandidateMatch): string {
    return { profile: 'chip', related: 'badge badge-keyword-related', text: 'badge badge-keyword', partial: 'badge badge-keyword-partial', filters: '' }[match.match_type];
  }

  matchTooltip(match: CandidateMatch): string {
    return {
      profile: 'Every requirement is in this candidate\'s search profile; score 0-100 from relevant years of experience',
      related: 'Found through an equivalent term (e.g. "tutor" for "teacher") or only through a degree',
      text: 'Not in the search profile, but the resume text mentions it',
      partial: 'Meets only some of the requirements - ranked below candidates who meet them all',
      filters: '',
    }[match.match_type];
  }

  filterBadgeClass(match: CandidateMatch): string {
    if (match.filters_passed === match.filters.length) return 'badge-match';
    return match.filters_passed === 0 ? 'badge-nomatch' : 'badge-partial';
  }

  filterBadgeLabel(match: CandidateMatch): string {
    const total = match.filters.length;
    return match.filters_passed === total || match.filters_passed === 0 ? 'Filters' : `Filters (${match.filters_passed}/${total})`;
  }

  /** "✓ Location: Coimbatore, Tamil Nadu, India, ✗ Meditator: not on file (wanted Yes)" */
  filterBadgeDetail(match: CandidateMatch): string {
    return match.filters
      .map((f) => {
        const have = f.candidate_value ?? 'not on file';
        const wanted = f.status === 'pass' || !f.requested ? '' : ` (wanted ${f.requested})`;
        return `${STATUS_MARK[f.status]} ${f.label}: ${have}${wanted}`;
      })
      .join(', ');
  }

  years(value: number): string {
    return years(value);
  }

  useExample(example: string): void {
    this.state.setQueryText(example);
  }

  onLoadMore(): void {
    void this.state.loadMore();
  }

  onSearch(): void {
    void this.state.search();
  }

  onSearchShortcut(): void {
    if (!this.state.loading() && this.state.queryText().trim()) {
      this.onSearch();
    }
  }

  onToggleDetail(candidateId: string): void {
    void this.state.toggleDetail(candidateId);
  }

  onDetailSaved(candidateId: string): void {
    void this.state.reloadDetail(candidateId);
  }
}

function years(value: number): string {
  return `${Number.isInteger(value) ? value : value.toFixed(1)} yr${value === 1 ? '' : 's'}`;
}
