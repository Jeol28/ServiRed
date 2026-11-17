import { Pipe, PipeTransform } from '@angular/core';
import { duracion, fechaCorta } from './etiquetas';

@Pipe({ name: 'fechaCorta' })
export class FechaCortaPipe implements PipeTransform {
  transform(iso?: string | null): string {
    return fechaCorta(iso);
  }
}

@Pipe({ name: 'duracion' })
export class DuracionPipe implements PipeTransform {
  transform(minutos?: number | null): string {
    return duracion(minutos);
  }
}
