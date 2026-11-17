import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { interceptorCorrelacion, interceptorJwt } from '../http/interceptores';
import { NuevaDenuncia } from '../modelos';
import { ServicioSesion } from '../sesion/servicio-sesion';
import { ApiDenuncias } from './api-denuncias';

const denuncia: NuevaDenuncia = {
  tipoServicio: 'ENERGIA', ubicacion: { latitud: 4.6, longitud: -74.08 }, descripcion: 'Conexión ilegal al poste',
};

describe('ApiDenuncias (RNF-SEG4)', () => {
  let api: ApiDenuncias;
  let backend: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([interceptorCorrelacion, interceptorJwt])),
        provideHttpClientTesting(),
      ],
    });
    api = TestBed.inject(ApiDenuncias);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('no envía JWT ni correlationId aunque haya una sesión abierta', () => {
    TestBed.inject(ServicioSesion).iniciar('a@b.co', 'una clave larga').subscribe();
    backend.expectOne('/api/v1/auth/login').flush({
      tokenAcceso: 'A1', tipoToken: 'Bearer', expiraEn: 900, tokenRefresco: 'R1', cuenta: { idCuenta: 'c', rol: 'CIUDADANO' },
    });

    api.enviar(denuncia).subscribe();
    const req = backend.expectOne('/api/v1/denuncias');
    expect(req.request.headers.keys()).toEqual([]);
    expect(req.request.withCredentials).toBe(false);
    expect(req.request.body).toEqual(denuncia);
    req.flush({ idDenuncia: 'd', estado: 'RECIBIDA', fechaRecepcion: '', tieneEvidencia: false });
  });

  it('con foto envía multipart con la denuncia en JSON y un nombre de archivo neutro', async () => {
    const foto = new File([new Uint8Array([0xff, 0xd8, 0xff])], 'IMG_juan_perez_casa.jpg', { type: 'image/jpeg' });
    api.enviar(denuncia, foto).subscribe();
    const req = backend.expectOne('/api/v1/denuncias');
    const cuerpo = req.request.body as FormData;
    expect(cuerpo).toBeInstanceOf(FormData);
    const parte = cuerpo.get('denuncia') as Blob;
    expect(parte.type).toBe('application/json');
    expect(JSON.parse(await parte.text())).toEqual(denuncia);
    expect((cuerpo.get('foto') as File).name).toBe('foto');
    req.flush({ idDenuncia: 'd', estado: 'RECIBIDA', fechaRecepcion: '', tieneEvidencia: true });
  });
});
