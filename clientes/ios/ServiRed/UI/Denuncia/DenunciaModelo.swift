import Foundation
import Observation

/// Denuncia anónima (RF-5): no requiere cuenta ni guarda nada en el teléfono.
@MainActor
@Observable
final class DenunciaModelo {
    var tipo: TipoServicio?
    var ubicacion: Ubicacion?
    var descripcion = ""
    private(set) var foto: String?
    private(set) var enviando = false
    var error: String?
    private(set) var enviada = false

    private let repo: RepositorioDenuncias
    private let proveedor: ProveedorUbicacion
    private let descartarFoto: (String) -> Void

    init(repo: RepositorioDenuncias, ubicacion: ProveedorUbicacion, descartarFoto: @escaping (String) -> Void = { _ in }) {
        self.repo = repo
        proveedor = ubicacion
        self.descartarFoto = descartarFoto
    }

    func usarMiUbicacion() async {
        if let u = await proveedor.actual() { ubicacion = u } else { error = "No se pudo obtener la ubicación. Márquela en el mapa." }
    }

    func ponerFoto(_ nombre: String?) {
        foto.map(descartarFoto)
        foto = nombre
    }

    func enviar() async {
        guard let tipo else { error = "Elija el servicio."; return }
        guard let ubicacion else { error = "Indique dónde ocurre."; return }
        let denuncia = NuevaDenuncia(tipoServicio: tipo, ubicacion: ubicacion, descripcion: descripcion)
        if let e = Reglas.validarDenuncia(denuncia).first { error = e; return }
        enviando = true
        defer { enviando = false }
        do {
            try await repo.enviar(denuncia, foto: foto)
            // Se olvida todo el contenido: no queda nada que vincule la denuncia con el teléfono.
            foto = nil
            self.tipo = nil
            self.ubicacion = nil
            descripcion = ""
            enviada = true
        } catch {
            self.error = ErrorApp.de(error).mensaje
        }
    }

    func nueva() { enviada = false; error = nil }
}
