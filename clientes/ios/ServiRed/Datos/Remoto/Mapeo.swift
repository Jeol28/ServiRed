import Foundation

extension UbicacionDTO {
    var dominio: Ubicacion { Ubicacion(latitud: latitud, longitud: longitud) }
}

extension Ubicacion {
    var dto: UbicacionDTO { UbicacionDTO(latitud: latitud, longitud: longitud) }
}

extension ConteoDTO {
    var dominio: Conteo { Conteo(confirmaciones: confirmaciones, controversias: controversias, netas: confirmacionesNetas) }
}

extension ReporteDTO {
    func dominio() throws -> Reporte {
        Reporte(
            id: idReporte,
            tipoServicio: tipoServicio,
            ubicacion: ubicacion.dominio,
            codigoMunicipio: codigoMunicipio,
            nombreMunicipio: nombreMunicipio,
            inicioEstimado: try Fechas.exigir(inicioEstimado),
            magnitud: magnitud,
            descripcion: descripcion,
            estado: estado,
            fechaRegistro: try Fechas.exigir(fechaRegistro),
            fechaRestablecimiento: fechaRestablecimiento.flatMap(Fechas.desde),
            duracionMinutos: duracionMinutos,
            validacionCorte: validacion?.corte?.dominio,
            validacionRestablecimiento: validacion?.restablecimiento?.dominio,
            evidencias: try (evidencias ?? []).map {
                Evidencia(id: $0.idEvidencia, tipoContenido: $0.tipoContenido, tamanoBytes: $0.tamanoBytes,
                          creadaEn: try Fechas.exigir($0.creadaEn))
            },
            historia: try (historia ?? []).map {
                EntradaHistoria(estado: $0.estado, fecha: try Fechas.exigir($0.fecha), detalle: $0.detalle)
            }
        )
    }
}

extension ReporteCercanoDTO {
    func dominio() throws -> ReporteCercano {
        ReporteCercano(
            id: idReporte, tipoServicio: tipoServicio, magnitud: magnitud, estado: estado,
            inicioEstimado: try Fechas.exigir(inicioEstimado), ubicacion: ubicacion.dominio,
            distanciaMetros: distanciaMetros, corte: validacionCorte?.dominio, restablecimiento: validacionRestablecimiento?.dominio
        )
    }
}

extension NuevoReporte {
    var dto: NuevoReporteDTO {
        let texto = descripcion?.trimmingCharacters(in: .whitespacesAndNewlines)
        return NuevoReporteDTO(
            tipoServicio: tipoServicio, ubicacion: ubicacion.dto, inicioEstimado: Fechas.texto(inicioEstimado),
            magnitud: magnitud, descripcion: texto?.isEmpty == false ? texto : nil
        )
    }
}

extension NuevoReporteDTO {
    func dominio() throws -> NuevoReporte {
        NuevoReporte(tipoServicio: tipoServicio, ubicacion: ubicacion.dominio,
                     inicioEstimado: try Fechas.exigir(inicioEstimado), magnitud: magnitud, descripcion: descripcion)
    }
}

extension NuevaDenuncia {
    var dto: NuevaDenunciaDTO {
        NuevaDenunciaDTO(tipoServicio: tipoServicio, ubicacion: ubicacion.dto,
                         descripcion: descripcion.trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

extension CuentaDTO {
    var dominio: Cuenta { Cuenta(id: idCuenta, correo: correo, nombre: nombre, rol: rol) }
}
