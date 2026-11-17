import Foundation
import SwiftData

/// Copia local de un reporte del ciudadano (el JSON del contrato), para verlo sin conexión.
@Model
final class ReporteGuardado {
    @Attribute(.unique) var id: String
    var json: Data
    var fechaRegistro: Date

    init(id: String, json: Data, fechaRegistro: Date) {
        self.id = id
        self.json = json
        self.fechaRegistro = fechaRegistro
    }
}

/// Reporte por enviar (outbox del teléfono).
@Model
final class PendienteGuardado {
    @Attribute(.unique) var idLocal: UUID
    var correlationId: String
    /// NuevoReporteDTO en JSON.
    var json: Data
    /// Nombres de las fotos ya preparadas (sin metadatos) en la carpeta de fotos.
    var fotos: [String]
    var fotosEnviadas: Int
    var estado: String
    var error: String?
    var creadoEn: Date
    var idReporte: String?

    init(_ p: FilaPendiente) {
        idLocal = p.idLocal
        correlationId = p.correlationId
        json = p.json
        fotos = p.fotos
        fotosEnviadas = p.fotosEnviadas
        estado = p.estado
        error = p.error
        creadoEn = p.creadoEn
        idReporte = p.idReporte
    }
}

struct FilaReporte: Sendable {
    var id: String
    var json: Data
    var fechaRegistro: Date
}

struct FilaPendiente: Sendable, Equatable {
    var idLocal = UUID()
    var correlationId: String
    var json: Data
    var fotos: [String]
    var fotosEnviadas = 0
    var estado: String
    var error: String?
    var creadoEn: Date
    var idReporte: String?
}

/// Acceso a SwiftData desde un actor propio (fuera del hilo principal).
@ModelActor
actor AlmacenLocal {
    static func contenedor(enMemoria: Bool = false) throws -> ModelContainer {
        try ModelContainer(
            for: ReporteGuardado.self, PendienteGuardado.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: enMemoria)
        )
    }

    func reportes() -> [FilaReporte] {
        let orden = FetchDescriptor<ReporteGuardado>(sortBy: [SortDescriptor(\.fechaRegistro, order: .reverse)])
        return ((try? modelContext.fetch(orden)) ?? []).map { FilaReporte(id: $0.id, json: $0.json, fechaRegistro: $0.fechaRegistro) }
    }

    func guardar(_ filas: [FilaReporte]) {
        for f in filas {
            let id = f.id
            if let existente = try? modelContext.fetch(FetchDescriptor<ReporteGuardado>(predicate: #Predicate { $0.id == id })).first {
                existente.json = f.json
                existente.fechaRegistro = f.fechaRegistro
            } else {
                modelContext.insert(ReporteGuardado(id: f.id, json: f.json, fechaRegistro: f.fechaRegistro))
            }
        }
        try? modelContext.save()
    }

    func reemplazar(_ filas: [FilaReporte]) {
        try? modelContext.delete(model: ReporteGuardado.self)
        filas.forEach { modelContext.insert(ReporteGuardado(id: $0.id, json: $0.json, fechaRegistro: $0.fechaRegistro)) }
        try? modelContext.save()
    }

    func pendientes(soloPorEnviar: Bool = false) -> [FilaPendiente] {
        let orden = FetchDescriptor<PendienteGuardado>(sortBy: [SortDescriptor(\.creadoEn)])
        return ((try? modelContext.fetch(orden)) ?? [])
            .filter { !soloPorEnviar || $0.estado != EstadoEnvio.FALLIDO.rawValue }
            .map {
                FilaPendiente(idLocal: $0.idLocal, correlationId: $0.correlationId, json: $0.json, fotos: $0.fotos,
                              fotosEnviadas: $0.fotosEnviadas, estado: $0.estado, error: $0.error,
                              creadoEn: $0.creadoEn, idReporte: $0.idReporte)
            }
    }

    func pendiente(_ idLocal: UUID) -> FilaPendiente? { pendientes().first { $0.idLocal == idLocal } }

    func insertar(_ p: FilaPendiente) {
        modelContext.insert(PendienteGuardado(p))
        try? modelContext.save()
    }

    func actualizar(_ p: FilaPendiente) {
        let id = p.idLocal
        guard let e = try? modelContext.fetch(FetchDescriptor<PendienteGuardado>(predicate: #Predicate { $0.idLocal == id })).first else { return }
        e.fotosEnviadas = p.fotosEnviadas
        e.estado = p.estado
        e.error = p.error
        e.idReporte = p.idReporte
        try? modelContext.save()
    }

    func borrarPendiente(_ idLocal: UUID) {
        try? modelContext.delete(model: PendienteGuardado.self, where: #Predicate { $0.idLocal == idLocal })
        try? modelContext.save()
    }

    func borrarTodo() {
        try? modelContext.delete(model: ReporteGuardado.self)
        try? modelContext.delete(model: PendienteGuardado.self)
        try? modelContext.save()
    }
}

/// Carpeta privada de las fotos preparadas (excluida de las copias de seguridad).
struct CarpetaFotos: Sendable {
    let url: URL

    static func predeterminada() -> CarpetaFotos {
        var u = URL.applicationSupportDirectory.appending(path: "fotos", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        var valores = URLResourceValues()
        valores.isExcludedFromBackup = true
        try? u.setResourceValues(valores)
        return CarpetaFotos(url: u)
    }

    func ruta(_ nombre: String) -> URL { url.appending(path: nombre) }
    func datos(_ nombre: String) -> Data? { try? Data(contentsOf: ruta(nombre)) }
    func borrar(_ nombre: String) { try? FileManager.default.removeItem(at: ruta(nombre)) }
    func borrarTodo() {
        let archivos = (try? FileManager.default.contentsOfDirectory(at: url, includingPropertiesForKeys: nil)) ?? []
        archivos.forEach { try? FileManager.default.removeItem(at: $0) }
    }
}
