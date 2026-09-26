import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, BaseRouteReuseStrategy, DetachedRouteHandle } from '@angular/router';

/**
 * Pages whose route has data: { keepAlive: true } are parked, not destroyed, when you navigate away - and
 * re-attached as they were when you come back: uploaded panels, unsaved edits, open results, work in progress.
 */
@Injectable()
export class KeepAliveRouteStrategy extends BaseRouteReuseStrategy {
  private readonly parked = new Map<string, DetachedRouteHandle>();

  override shouldDetach(route: ActivatedRouteSnapshot): boolean {
    return route.data['keepAlive'] === true;
  }

  override store(route: ActivatedRouteSnapshot, handle: DetachedRouteHandle | null): void {
    if (handle) {
      this.parked.set(pathOf(route), handle);
    }
  }

  override shouldAttach(route: ActivatedRouteSnapshot): boolean {
    return this.parked.has(pathOf(route));
  }

  override retrieve(route: ActivatedRouteSnapshot): DetachedRouteHandle | null {
    return this.parked.get(pathOf(route)) ?? null;
  }
}

const pathOf = (route: ActivatedRouteSnapshot): string => route.routeConfig?.path ?? '';
