import { Component, HostListener, signal, viewChildren } from '@angular/core';
import { ResumeApiService } from '../../core/api.service';
import { ApplicantDetails, DuplicateMatch, EMPTY_APPLICANT_DETAILS } from '../../core/models';
import { CandidateRecordEditor } from '../../shared/candidate-record-editor/candidate-record-editor';

interface UploadPanel {
  uniquefileId: string;
  fileName: string;
  extractedText: string;
  applicantDetails: ApplicantDetails;
  extracting: boolean;
  error: string | null;
  duplicateOf: DuplicateMatch | null;
  saveError: string | null;
  saved: boolean;
  dirty: boolean;
  expanded: boolean;
}

/** Which color the panel's file name should be, reflecting where it is in the extract → review → save lifecycle. */
type PanelState = 'extracting' | 'error' | 'duplicate' | 'save-error' | 'dirty' | 'saved' | 'ready';

/**
 * Handles both a single resume and a batch of them with the same workflow: pick file(s), each gets its
 * own collapsible panel that auto-extracts text and asks the LLM to suggest applicant details, then a
 * CandidateRecordEditor for review. Each panel has its own Save; "Save All" saves every panel at once,
 * waiting for any still-extracting panels rather than silently skipping them.
 */
@Component({
  selector: 'app-upload',
  imports: [CandidateRecordEditor],
  templateUrl: './upload.html',
  styleUrl: './upload.css',
})
export class Upload {
  readonly panels = signal<UploadPanel[]>([]);
  readonly savingAll = signal(false);
  readonly waitingForExtraction = signal(false);

  private readonly editors = viewChildren(CandidateRecordEditor);
  /** Every extraction promise started so far, including already-settled ones - Save All awaits this
   *  snapshot so a panel that's still extracting when clicked gets waited for instead of silently skipped. */
  private readonly pendingExtractions: Promise<void>[] = [];

  constructor(private readonly api: ResumeApiService) {}

  async onFilesSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    if (!files.length) {
      return;
    }
    const newPanels: UploadPanel[] = files.map((file) => ({
      uniquefileId: crypto.randomUUID(),
      fileName: file.name,
      extractedText: '',
      applicantDetails: { ...EMPTY_APPLICANT_DETAILS },
      extracting: true,
      error: null,
      duplicateOf: null,
      saveError: null,
      saved: false,
      dirty: false,
      expanded: false,
    }));
    this.panels.update((existing) => [...existing, ...newPanels]);
    const extractions = files.map((file, i) => this.extractAndSuggest(file, newPanels[i].uniquefileId));
    this.pendingExtractions.push(...extractions);
    await Promise.all(extractions);
  }

  private async extractAndSuggest(file: File, uniquefileId: string): Promise<void> {
    try {
      const extracted = await this.api.extractText(file);
      if (extracted.duplicate_of) {
        // Already have this exact resume content on file - skip the AI-suggestion call entirely
        // rather than pay for it on a candidate we won't save.
        this.updatePanel(uniquefileId, {
          extractedText: extracted.extracted_text,
          duplicateOf: extracted.duplicate_of,
          extracting: false,
        });
        return;
      }
      let applicantDetails = { ...EMPTY_APPLICANT_DETAILS };
      try {
        applicantDetails = await this.api.suggestApplicantDetails(extracted.extracted_text);
      } catch (err) {
        console.warn('Could not fetch applicant-detail suggestions - form stays blank/manual.', err);
      }
      this.updatePanel(uniquefileId, { extractedText: extracted.extracted_text, applicantDetails, extracting: false });
    } catch (err) {
      console.error('Extraction failed', err);
      this.updatePanel(uniquefileId, {
        extracting: false,
        error: 'Could not extract text from this file - only .pdf, .doc and .docx are supported.',
      });
    }
  }

  togglePanel(uniquefileId: string): void {
    this.panels.update((panels) =>
      panels.map((p) => (p.uniquefileId === uniquefileId ? { ...p, expanded: !p.expanded } : p)),
    );
  }

  onPanelSaved(uniquefileId: string): void {
    this.updatePanel(uniquefileId, { saved: true, saveError: null });
  }

  onPanelDirtyChanged(uniquefileId: string, dirty: boolean): void {
    this.updatePanel(uniquefileId, { dirty });
  }

  onPanelSaveFailed(uniquefileId: string, message: string): void {
    this.updatePanel(uniquefileId, { saveError: message });
  }

  removePanel(uniquefileId: string): void {
    this.panels.update((panels) => panels.filter((p) => p.uniquefileId !== uniquefileId));
  }

  panelState(panel: UploadPanel): PanelState {
    if (panel.extracting) {
      return 'extracting';
    }
    if (panel.error) {
      return 'error';
    }
    if (panel.duplicateOf) {
      return 'duplicate';
    }
    if (panel.saveError) {
      return 'save-error';
    }
    if (panel.dirty) {
      return 'dirty';
    }
    if (panel.saved) {
      return 'saved';
    }
    return 'ready';
  }

  /** Ctrl+Enter anywhere on the page except inside a panel (which handles - and stops - its own shortcut). */
  @HostListener('keydown.control.enter', ['$event'])
  onSaveAllShortcut(event: Event): void {
    if (this.panels().length && !this.savingAll() && !this.waitingForExtraction()) {
      event.preventDefault();
      void this.onSaveAll();
    }
  }

  /** Saves every panel that's ready and not already saved. Panels still extracting when this is called
   *  are waited for - not silently skipped - so "Save All" clicked right after picking files still saves
   *  everything. A repeat click only retries panels that failed or haven't been saved yet - it doesn't
   *  needlessly re-upload ones already saved and unchanged since. */
  async onSaveAll(): Promise<void> {
    if (this.pendingExtractions.length) {
      this.waitingForExtraction.set(true);
      try {
        await Promise.all(this.pendingExtractions);
      } finally {
        this.waitingForExtraction.set(false);
      }
    }
    this.savingAll.set(true);
    try {
      const panelsById = new Map(this.panels().map((p) => [p.uniquefileId, p]));
      const editorsToSave = this.editors().filter((editor) => {
        const panel = panelsById.get(editor.uniquefileId());
        return !panel?.saved || panel.dirty;
      });
      await Promise.all(editorsToSave.map((editor) => editor.save()));
    } finally {
      this.savingAll.set(false);
    }
  }

  private updatePanel(uniquefileId: string, patch: Partial<UploadPanel>): void {
    this.panels.update((panels) => panels.map((p) => (p.uniquefileId === uniquefileId ? { ...p, ...patch } : p)));
  }
}
