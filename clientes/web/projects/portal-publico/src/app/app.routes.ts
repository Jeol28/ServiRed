import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', title: 'Mapa', loadComponent: () => import('./paginas/inicio').then((m) => m.Inicio) },
  { path: 'indicadores', title: 'Indicadores', loadComponent: () => import('./paginas/indicadores').then((m) => m.Indicadores) },
  { path: 'datos-abiertos', title: 'Datos abiertos', loadComponent: () => import('./paginas/datos-abiertos').then((m) => m.DatosAbiertos) },
  { path: 'denunciar', title: 'Denuncia anónima', loadComponent: () => import('./paginas/denunciar').then((m) => m.Denunciar) },
  { path: '**', redirectTo: '' },
];
