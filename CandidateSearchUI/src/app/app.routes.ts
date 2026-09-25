import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'search', pathMatch: 'full' },
  { path: 'search', loadComponent: () => import('./features/search/search').then((m) => m.Search) },
  { path: 'upload', loadComponent: () => import('./features/upload/upload').then((m) => m.Upload) },
  { path: 'admin', loadComponent: () => import('./features/admin/admin').then((m) => m.Admin) },
];
