import { Component, input, output, signal, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ResumeApiService } from '../../core/api.service';
import { ApplicantDetails, EMPTY_APPLICANT_DETAILS, UploadResponse } from '../../core/models';

// Human-friendly option labels, matching ApplicantNormalization.java's known values exactly, so a value
// picked from the list normalizes correctly server-side. Editable comboboxes (input + datalist), not
// closed <select>s, so HR can still type a value that isn't in this list.
export const GENDER_OPTIONS = ['Male', 'Female'];
export const MARITAL_STATUS_OPTIONS = ['Single', 'Married', 'Divorced'];
export const EXPERIENCE_OPTIONS = ['0 ~ 3years', '4 ~ 6years', '7 ~ 10years', '10+years'];
export const NOTICE_PERIOD_OPTIONS = [
  'Immediate', 'Less than a Week', 'Less than 15 Days', '1 Month', '2 Months', '3 Months', '6 Months', 'More than 6 Months',
];
export const STAY_IN_ASHRAM_OPTIONS = ['Yes', 'No', 'No, I would like to work from above stated preferred location'];
export const ANY_KIND_JOB_OPTIONS = ['Yes', 'No, only relevant to my Experience', 'No, only relevant to my Qualification'];
export const DURATION_WITH_ISHA_OPTIONS = ['Minimum 1 Year', '1 ~ 2 Years', '2 ~ 3 Years', '3 ~ 4 Years', '5+ Years'];
export const DONE_ISHA_PROGRAM_OPTIONS = ['Yes', 'No'];
export const IS_MEDITATOR_OPTIONS = ['Yes', 'No', 'IEO'];

/**
 * Editable resume text + applicant details, with its own Save button - reused for fresh single-file
 * uploads, each panel in a multi-file bulk upload, and editing an already-saved candidate from search
 * results. Callers just supply the initial values; this component owns the submit call and its own
 * loading/success/error state, and tells the caller when a save succeeds via (saved).
 */
@Component({
  selector: 'app-candidate-record-editor',
  imports: [FormsModule],
  templateUrl: './candidate-record-editor.html',
  styleUrl: './candidate-record-editor.css',
})
export class CandidateRecordEditor implements OnInit {
  readonly genderOptions = GENDER_OPTIONS;
  readonly maritalStatusOptions = MARITAL_STATUS_OPTIONS;
  readonly experienceOptions = EXPERIENCE_OPTIONS;
  readonly noticePeriodOptions = NOTICE_PERIOD_OPTIONS;
  readonly stayInAshramOptions = STAY_IN_ASHRAM_OPTIONS;
  readonly anyKindJobOptions = ANY_KIND_JOB_OPTIONS;
  readonly durationWithIshaOptions = DURATION_WITH_ISHA_OPTIONS;
  readonly doneIshaProgramOptions = DONE_ISHA_PROGRAM_OPTIONS;
  readonly isMeditatorOptions = IS_MEDITATOR_OPTIONS;

  readonly candidateId = input.required<string>();
  /** The candidate ID is the upsert key - editable for a not-yet-saved record, locked once it already exists. */
  readonly idEditable = input(true);
  readonly initialResumeName = input('');
  readonly initialResumeText = input('');
  readonly initialApplicantDetails = input<ApplicantDetails>({ ...EMPTY_APPLICANT_DETAILS });
  readonly saved = output<UploadResponse>();
  /** Fires whenever the panel's edited-since-load/save state flips - lets a parent (e.g. Upload) reflect it visually. */
  readonly dirtyChange = output<boolean>();
  /** Fires whenever a save attempt fails, with the error message - lets a parent (e.g. Upload) flag the panel. */
  readonly saveFailed = output<string>();

  editableId = '';
  resumeName = '';
  resumeText = '';
  details: ApplicantDetails = { ...EMPTY_APPLICANT_DETAILS };

  readonly submitting = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);
  private dirty = false;

  constructor(private readonly api: ResumeApiService) {}

  ngOnInit(): void {
    this.editableId = this.candidateId();
    this.resumeName = this.initialResumeName();
    this.resumeText = this.initialResumeText();
    this.details = { ...this.initialApplicantDetails() };
  }

  /** Bound to (input)/(change) on the whole field container, so any edit to any field is caught without wiring each one individually. */
  onFieldChanged(): void {
    if (!this.dirty) {
      this.dirty = true;
      this.dirtyChange.emit(true);
    }
  }

  /** Ctrl+Enter anywhere in the fields - stops here so it doesn't also trigger a parent's "Save All" shortcut. */
  onSaveShortcut(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    if (!this.submitting() && this.resumeText.trim()) {
      void this.save();
    }
  }

  /** Public so a parent (e.g. Upload's "Save All") can trigger a save on every rendered panel - guards
   *  against blank resume text itself, since a bulk caller bypasses the Save button's [disabled] state. */
  async save(): Promise<boolean> {
    if (!this.resumeText.trim()) {
      return this.fail('Resume text is required - this file may not have extracted any text (e.g. a scanned/image-only document).');
    }
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.successMessage.set(null);
    try {
      const response = await this.api.uploadResume(this.editableId, this.resumeName, this.resumeText, this.details);
      if (response.status) {
        this.successMessage.set(response.updated_existing ? response.message : `Saved as ${response.candidate_id}`);
        this.saved.emit(response);
        this.dirty = false;
        this.dirtyChange.emit(false);
        return true;
      }
      return this.fail(response.message);
    } catch (err) {
      console.error('Save failed', err);
      return this.fail('Save failed - is the backend running at localhost:8000?');
    } finally {
      this.submitting.set(false);
    }
  }

  private fail(message: string): false {
    this.errorMessage.set(message);
    this.saveFailed.emit(message);
    return false;
  }
}
