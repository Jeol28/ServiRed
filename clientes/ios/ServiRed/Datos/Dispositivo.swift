import BackgroundTasks
import CoreLocation
import Foundation
import Network
import UIKit

/// Ubicación puntual con CoreLocation, solo con la app en uso (nunca en segundo plano).
@MainActor
final class ProveedorUbicacionCoreLocation: NSObject, ProveedorUbicacion, CLLocationManagerDelegate {
    private let gestor = CLLocationManager()
    private var esperaPermiso: CheckedContinuation<Void, Never>?
    private var esperaUbicacion: CheckedContinuation<Ubicacion?, Never>?

    override init() {
        super.init()
        gestor.delegate = self
        gestor.desiredAccuracy = kCLLocationAccuracyBest
    }

    func actual() async -> Ubicacion? {
        if gestor.authorizationStatus == .notDetermined {
            await withCheckedContinuation { c in
                esperaPermiso = c
                gestor.requestWhenInUseAuthorization()
            }
        }
        guard [.authorizedWhenInUse, .authorizedAlways].contains(gestor.authorizationStatus) else { return nil }
        guard esperaUbicacion == nil else { return nil }
        return await withCheckedContinuation { c in
            esperaUbicacion = c
            gestor.requestLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let estado = manager.authorizationStatus
        MainActor.assumeIsolated {
            guard estado != .notDetermined else { return }
            esperaPermiso?.resume()
            esperaPermiso = nil
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let c = locations.last?.coordinate
        MainActor.assumeIsolated {
            esperaUbicacion?.resume(returning: c.map { Ubicacion(latitud: redondear($0.latitude), longitud: redondear($0.longitude)) })
            esperaUbicacion = nil
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        MainActor.assumeIsolated {
            esperaUbicacion?.resume(returning: nil)
            esperaUbicacion = nil
        }
    }
}

/// 5 decimales (~1 m) bastan para un reporte.
func redondear(_ v: Double) -> Double { (v * 1e5).rounded() / 1e5 }

/**
 Prepara una foto antes de guardarla o enviarla: la endereza, la reduce a 2560
 px como máximo y la vuelve a codificar en JPEG. Al redibujarla se descartan
 TODOS los metadatos (GPS, modelo, fecha); el servidor lo vuelve a hacer,
 pero así nunca salen del teléfono.
 */
struct PreparadorFotos: Sendable {
    let carpeta: CarpetaFotos
    private let maxLado: CGFloat = 2560

    func preparar(datos: Data) -> String? {
        guard let imagen = UIImage(data: datos) else { return nil }
        return preparar(imagen: imagen)
    }

    func preparar(imagen: UIImage) -> String? {
        let escala = min(1, maxLado / max(imagen.size.width, imagen.size.height))
        let tamano = CGSize(width: (imagen.size.width * escala).rounded(), height: (imagen.size.height * escala).rounded())
        let formato = UIGraphicsImageRendererFormat()
        formato.scale = 1
        let limpia = UIGraphicsImageRenderer(size: tamano, format: formato).image { _ in
            imagen.draw(in: CGRect(origin: .zero, size: tamano)) // respeta la orientación
        }
        var calidad: CGFloat = 0.85
        var datos = limpia.jpegData(compressionQuality: calidad)
        while let d = datos, d.count > Reglas.maxBytesFoto, calidad > 0.4 {
            calidad -= 0.15
            datos = limpia.jpegData(compressionQuality: calidad)
        }
        guard let datos else { return nil }
        let nombre = "\(UUID().uuidString).jpg"
        do {
            try datos.write(to: carpeta.ruta(nombre), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return nombre
        } catch {
            return nil
        }
    }

    func descartar(_ nombre: String) { carpeta.borrar(nombre) }
}

/// Envío de pendientes en segundo plano (BGTaskScheduler; equivale a WorkManager).
struct EnvioEnSegundoPlano: ProgramadorEnvio {
    static let identificador = "co.edu.javeriana.servired.envio"

    func programar() {
        let solicitud = BGAppRefreshTaskRequest(identifier: Self.identificador)
        solicitud.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(solicitud)
    }
}

/// Cuando vuelve la conexión con la app abierta, envía lo pendiente.
final class MonitorRed: Sendable {
    private let monitor = NWPathMonitor()

    init(alConectar: @escaping @Sendable () -> Void) {
        monitor.pathUpdateHandler = { ruta in
            if ruta.status == .satisfied { alConectar() }
        }
        monitor.start(queue: DispatchQueue(label: "servired.red"))
    }

    deinit { monitor.cancel() }
}
