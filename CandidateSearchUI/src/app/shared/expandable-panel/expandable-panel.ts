import { Component, input, output } from '@angular/core';

/**
 * A card with a clickable header that expands/collapses its body - shared by the Upload panels and the Search
 * results so both behave the same. Slots: [panelTitle] and [panelActions] in the header, [panelSummary] always
 * visible under it, and the default slot as the body. The body stays rendered while collapsed ([hidden], not @if),
 * so e.g. Upload's "Save All" can still reach every editor.
 */
@Component({
  selector: 'app-expandable-panel',
  template: `
    <div class="card panel">
      <div class="panel-header" role="button" tabindex="0" [attr.aria-expanded]="expanded()"
           (click)="toggled.emit()" (keydown.enter)="toggled.emit()" (keydown.space)="$event.preventDefault(); toggled.emit()">
        <ng-content select="[panelTitle]" />
        <span class="panel-header-right">
          <ng-content select="[panelActions]" />
          <span class="chevron">{{ expanded() ? '▾' : '▸' }}</span>
        </span>
      </div>
      <ng-content select="[panelSummary]" />
      <div [hidden]="!expanded()"><ng-content /></div>
    </div>
  `,
})
export class ExpandablePanel {
  readonly expanded = input(false);
  readonly toggled = output<void>();
}
