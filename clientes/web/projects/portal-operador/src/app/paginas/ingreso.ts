import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ServicioSesion, mensajeDeError } from 'compartido';

/** Ingreso del personal. Las cuentas de ciudadano usan las apps móviles. */
@Component({
  selector: 'app-ingreso',
  imports: [FormsModule],
  template: `
    <section class="sr-tarjeta ingreso">
      <h1>Portal de operación</h1>
      <p class="sr-suave">Operadores, reguladores y administradores de SERVI-RED.</p>
      <form (ngSubmit)="entrar()" #f="ngForm">
        <label>Correo
          <input name="correo" type="email" autocomplete="username" required [(ngModel)]="correo" />
        </label>
        <label>Clave
          <input name="clave" type="password" autocomplete="current-password" required minlength="10"
                 [(ngModel)]="clave" />
        </label>
        @if (error()) {
          <p class="sr-aviso error" role="alert">{{ error() }}</p>
        }
        <button class="sr-primario" type="submit" [disabled]="f.invalid || enviando()">
          {{ enviando() ? 'Ingresando…' : 'Ingresar' }}
        </button>
      </form>
      <p class="volver"><a href="/">← Ir al portal público</a></p>
    </section>
  `,
  styles: `
    .ingreso { max-width: 380px; margin: 3rem auto; }
    form { display: flex; flex-direction: column; gap: .8rem; }
    .volver { margin: 1rem 0 0; font-size: .9rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Ingreso {
  private readonly sesion = inject(ServicioSesion);
  private readonly router = inject(Router);

  /** Ruta a la que volver tras ingresar (la pone el guardia). */
  readonly volver = input<string>();

  protected correo = '';
  protected clave = '';
  protected readonly enviando = signal(false);
  protected readonly error = signal<string | null>(null);

  protected entrar(): void {
    this.enviando.set(true);
    this.error.set(null);
    this.sesion.iniciar(this.correo.trim(), this.clave).subscribe({
      next: (cuenta) => {
        this.clave = '';
        if (cuenta.rol === 'CIUDADANO') {
          this.sesion.cerrar().subscribe();
          this.enviando.set(false);
          this.error.set('Este portal es para el personal. Los ciudadanos reportan desde la app móvil.');
          return;
        }
        const destino = this.volver();
        // Solo rutas internas: evita redirecciones abiertas.
        void this.router.navigateByUrl(destino?.startsWith('/') && !destino.startsWith('//') ? destino : '/');
      },
      error: (e: unknown) => {
        this.enviando.set(false);
        this.error.set((e as { status?: number }).status === 401
          ? 'Correo o clave incorrectos.' : mensajeDeError(e));
      },
    });
  }
}
