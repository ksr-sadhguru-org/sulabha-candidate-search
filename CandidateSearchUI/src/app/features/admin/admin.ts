import { Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ResumeApiService } from '../../core/api.service';
import { FieldCount } from '../../core/models';

const CONFIRM_PHRASE = 'DELETE ALL DATA';

@Component({
  selector: 'app-admin',
  imports: [FormsModule],
  templateUrl: './admin.html',
  styleUrl: './admin.css',
})
export class Admin {
  readonly confirmPhrase = CONFIRM_PHRASE;
  readonly confirmText = signal('');
  readonly clearing = signal(false);
  readonly dropping = signal(false);
  readonly creating = signal(false);
  readonly clearingCache = signal(false);
  readonly message = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  /** The growing field list job entries are labelled with - duplicates of the same field stand out here. */
  readonly fields = signal<FieldCount[]>([]);

  constructor(private readonly api: ResumeApiService) {
    void this.loadFields();
  }

  async loadFields(): Promise<void> {
    try {
      this.fields.set(await this.api.getFields());
    } catch (err) {
      console.error('Loading fields failed', err);
    }
  }

  get confirmed(): boolean {
    return this.confirmText().trim() === CONFIRM_PHRASE;
  }

  onConfirmTextInput(event: Event): void {
    this.confirmText.set((event.target as HTMLInputElement).value);
  }

  /** Recommended: empties all data, schema is never touched or missing even momentarily. */
  async onClearAllData(): Promise<void> {
    if (!this.confirmed) {
      return;
    }
    this.clearing.set(true);
    this.error.set(null);
    this.message.set(null);
    try {
      const response = await this.api.clearAllData();
      this.message.set(`${response.message}: ${(response.cleared_tables ?? []).join(', ')}`);
      this.confirmText.set('');
    } catch (err) {
      console.error('Clear all data failed', err);
      this.error.set('Failed to clear data - is the backend running at localhost:8000?');
    } finally {
      this.clearing.set(false);
    }
  }

  /** Matches the backend's /drop_all_tables exactly - removes the table schema too, not just rows. */
  async onDropAllTables(): Promise<void> {
    if (!this.confirmed) {
      return;
    }
    this.dropping.set(true);
    this.error.set(null);
    this.message.set(null);
    try {
      const response = await this.api.dropAllTables();
      this.message.set(`${response.message}: ${(response.dropped_tables ?? []).join(', ')}`);
      this.confirmText.set('');
    } catch (err) {
      console.error('Drop all tables failed', err);
      this.error.set('Failed to drop tables - is the backend running at localhost:8000?');
    } finally {
      this.dropping.set(false);
    }
  }

  /** Safe: forgets saved query readings so the next searches are read afresh; no candidate data is touched. */
  async onClearQueryCache(): Promise<void> {
    this.clearingCache.set(true);
    this.error.set(null);
    this.message.set(null);
    try {
      const response = await this.api.clearQueryCache();
      this.message.set(`${response.message} (${response.cleared_queries} saved queries)`);
    } catch (err) {
      console.error('Clear query cache failed', err);
      this.error.set('Failed to clear the query cache - is the backend running at localhost:8000?');
    } finally {
      this.clearingCache.set(false);
    }
  }

  async onCreateAllTables(): Promise<void> {
    this.creating.set(true);
    this.error.set(null);
    this.message.set(null);
    try {
      const response = await this.api.createAllTables();
      this.message.set(`${response.message}: ${(response.tables ?? []).join(', ')}`);
    } catch (err) {
      console.error('Create all tables failed', err);
      this.error.set('Failed to create tables - is the backend running at localhost:8000?');
    } finally {
      this.creating.set(false);
    }
  }
}
