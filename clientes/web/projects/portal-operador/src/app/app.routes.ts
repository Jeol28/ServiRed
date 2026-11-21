import { Routes } from '@angular/router';
import { guardiaAutenticado, guardiaRol } from 'compartido';

const PERSONAL = guardiaRol('OPERADOR', 'REGULADOR', 'ADMINISTRADOR');
const SUPERVISION = guardiaRol('REGULADOR', 'ADMINISTRADOR');

/**
 * Rutas del portal. Los guardias solo ordenan la navegación; el gateway y cada
 * servicio aplican la autorización real (RBAC con mínimo privilegio).
 */
export const routes: Routes = [
  { path: 'ingreso', loadComponent: () => import('./paginas/ingreso').then((m) => m.Ingreso), title: 'Ingreso' },
  { path: 'sin-permiso', loadComponent: () => import('./paginas/sin-permiso').then((m) => m.SinPermiso), title: 'Sin permiso' },
  {
    path: '',
    canActivate: [guardiaAutenticado],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'casos' },
      {
        path: 'casos', canActivate: [PERSONAL], title: 'Casos',
        loadComponent: () => import('./paginas/casos/bandeja-casos').then((m) => m.BandejaCasos),
      },
      {
        path: 'casos/:idCaso', canActivate: [PERSONAL], title: 'Caso',
        loadComponent: () => import('./paginas/casos/detalle-caso').then((m) => m.DetalleCaso),
      },
      {
        path: 'mapa', canActivate: [PERSONAL], title: 'Mapa operativo',
        loadComponent: () => import('./paginas/mapa-operativo').then((m) => m.MapaOperativo),
      },
      {
        path: 'moderacion', canActivate: [PERSONAL], title: 'Moderación',
        loadComponent: () => import('./paginas/moderacion/bandeja-moderacion').then((m) => m.BandejaModeracion),
      },
      {
        path: 'moderacion/:idDenuncia', canActivate: [PERSONAL], title: 'Denuncia',
        loadComponent: () => import('./paginas/moderacion/detalle-denuncia').then((m) => m.DetalleDenuncia),
      },
      {
        path: 'exportacion', canActivate: [SUPERVISION], title: 'Exportación regulatoria',
        loadComponent: () => import('./paginas/exportacion').then((m) => m.Exportacion),
      },
      {
        path: 'cuentas', canActivate: [guardiaRol('ADMINISTRADOR')], title: 'Cuentas',
        loadComponent: () => import('./paginas/cuentas').then((m) => m.Cuentas),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
