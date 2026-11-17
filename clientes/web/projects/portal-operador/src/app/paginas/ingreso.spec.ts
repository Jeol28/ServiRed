import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { ServicioSesion } from 'compartido';
import { Ingreso } from './ingreso';

function respuesta(rol: string) {
  return { tokenAcceso: 'A', tipoToken: 'Bearer', expiraEn: 900, tokenRefresco: 'R', cuenta: { idCuenta: 'c', rol } };
}

describe('Ingreso', () => {
  let backend: HttpTestingController;
  let navegar: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      imports: [Ingreso],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    backend = TestBed.inject(HttpTestingController);
    navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  });

  function entrar(volver?: string) {
    const f = TestBed.createComponent(Ingreso);
    if (volver) {
      f.componentRef.setInput('volver', volver);
    }
    const c = f.componentInstance as unknown as { correo: string; clave: string; entrar(): void };
    c.correo = ' op@servired.co ';
    c.clave = 'una clave larga';
    c.entrar();
    return f;
  }

  it('el personal entra y vuelve a la ruta interna pedida', () => {
    entrar('/casos/1');
    const req = backend.expectOne('/api/v1/auth/login');
    expect(req.request.body.correo).toBe('op@servired.co');
    req.flush(respuesta('OPERADOR'));
    expect(navegar).toHaveBeenCalledWith('/casos/1');
  });

  it('no sigue redirecciones a otros sitios', () => {
    entrar('//malo.example.com');
    backend.expectOne('/api/v1/auth/login').flush(respuesta('REGULADOR'));
    expect(navegar).toHaveBeenCalledWith('/');
  });

  it('rechaza las cuentas de ciudadano y cierra su sesión', () => {
    const f = entrar();
    backend.expectOne('/api/v1/auth/login').flush(respuesta('CIUDADANO'));
    backend.expectOne('/api/v1/auth/cerrar-sesion').flush(null);
    f.detectChanges();
    expect(TestBed.inject(ServicioSesion).autenticado()).toBe(false);
    expect(f.nativeElement.textContent).toContain('Este portal es para el personal');
    expect(navegar).not.toHaveBeenCalled();
  });

  it('credenciales incorrectas muestran un mensaje sin detalles', () => {
    const f = entrar();
    backend.expectOne('/api/v1/auth/login').flush({ title: 'x' }, { status: 401, statusText: 'No autorizado' });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Correo o clave incorrectos.');
  });
});
