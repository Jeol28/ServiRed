import MapLibre
import SwiftUI

/// Marca del mapa. Con `icono` se dibuja el símbolo; `propio` agrega un anillo oscuro.
struct MarcaMapa: Equatable, Identifiable {
    var id: String
    var ubicacion: Ubicacion
    var colorHex: String
    var icono: String? = nil
    var propio = false
}

/// Mapa base vectorial de OpenFreeMap, estilo Positron (claro, para que resalten las marcas).
/// Sin clave ni cuenta; el estilo trae su atribución (OpenFreeMap, OpenMapTiles, OpenStreetMap).
enum EstiloMapa {
    static let url = URL(string: "https://tiles.openfreemap.org/styles/positron")!
}

/**
 Mapa MapLibre con el mapa base de OpenFreeMap (igual que en Android):
 - `zonas`: capa nacional anónima, visible solo con el mapa alejado.
 - `marcas`: íconos tocables (`alTocarMarca`).
 - `usuario`: punto azul de la ubicación del ciudadano.
 - `seleccion`: punto elegido tocando el mapa (`alElegir`).
 - `alMoverCentro`: centro del mapa cada vez que se detiene.
 - `recentrar`: al cambiar, vuelve a `centro` aunque sea el mismo punto.
 */
struct MapaOSM: UIViewRepresentable {
    var marcas: [MarcaMapa] = []
    var zonas: [Zona] = []
    var usuario: Ubicacion?
    var seleccion: Ubicacion?
    var centro: Ubicacion?
    var zoom: Double = 15
    var recentrar = 0
    var alElegir: ((Ubicacion) -> Void)?
    var alTocarMarca: ((String) -> Void)?
    var alMoverCentro: ((Ubicacion) -> Void)?

    func makeCoordinator() -> Coordinador { Coordinador(self) }

    func makeUIView(context: Context) -> MLNMapView {
        let mapa = MLNMapView(frame: .zero, styleURL: EstiloMapa.url)
        mapa.delegate = context.coordinator
        mapa.allowsRotating = false
        mapa.allowsTilting = false
        mapa.setCenter(CLLocationCoordinate2D(latitude: 4.6, longitude: -74.1), zoomLevel: 5, animated: false)
        let toque = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinador.tocado(_:)))
        mapa.gestureRecognizers?.compactMap { $0 as? UITapGestureRecognizer }
            .filter { $0.numberOfTapsRequired == 2 }
            .forEach { toque.require(toFail: $0) }
        mapa.addGestureRecognizer(toque)
        mapa.accessibilityLabel = "Mapa"
        return mapa
    }

    func updateUIView(_ mapa: MLNMapView, context: Context) {
        context.coordinator.padre = self
        context.coordinator.actualizar(mapa)
    }

    @MainActor
    final class Coordinador: NSObject, @preconcurrency MLNMapViewDelegate {
        var padre: MapaOSM
        private var estilo: MLNStyle?
        private var ultimoCentro: Ubicacion?
        private var ultimoRecentrar = 0

        init(_ padre: MapaOSM) { self.padre = padre }

        func mapView(_ mapa: MLNMapView, didFinishLoading style: MLNStyle) {
            // Zonas nacionales: solo con el mapa alejado (en el barrio estorbarían).
            let zonas = MLNShapeSource(identifier: "zonas", shape: nil, options: nil)
            style.addSource(zonas)
            let capaZonas = MLNCircleStyleLayer(identifier: "capa-zonas", source: zonas)
            capaZonas.circleRadius = NSExpression(mglJSONObject: ["interpolate", ["linear"], ["get", "reportes"], 3, 10, 30, 26])
            capaZonas.circleColor = NSExpression(forConstantValue: UIColor(hex: "#DC2626"))
            capaZonas.circleOpacity = NSExpression(forConstantValue: 0.28)
            capaZonas.circleStrokeColor = NSExpression(forConstantValue: UIColor(hex: "#DC2626"))
            capaZonas.circleStrokeWidth = NSExpression(forConstantValue: 1)
            capaZonas.maximumZoomLevel = 12.5
            style.addLayer(capaZonas)

            let usuario = MLNShapeSource(identifier: "usuario", shape: nil, options: nil)
            style.addSource(usuario)
            let halo = MLNCircleStyleLayer(identifier: "capa-usuario-halo", source: usuario)
            halo.circleRadius = NSExpression(forConstantValue: 20)
            halo.circleColor = NSExpression(forConstantValue: UIColor(hex: "#2563EB"))
            halo.circleOpacity = NSExpression(forConstantValue: 0.18)
            style.addLayer(halo)
            let punto = MLNCircleStyleLayer(identifier: "capa-usuario", source: usuario)
            punto.circleRadius = NSExpression(forConstantValue: 8)
            punto.circleColor = NSExpression(forConstantValue: UIColor(hex: "#2563EB"))
            punto.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            punto.circleStrokeWidth = NSExpression(forConstantValue: 3)
            style.addLayer(punto)

            let seleccion = MLNShapeSource(identifier: "seleccion", shape: nil, options: nil)
            style.addSource(seleccion)
            let capaSel = MLNCircleStyleLayer(identifier: "capa-seleccion", source: seleccion)
            capaSel.circleRadius = NSExpression(forConstantValue: 11)
            capaSel.circleColor = NSExpression(forConstantValue: UIColor(hex: "#0B5394"))
            capaSel.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            capaSel.circleStrokeWidth = NSExpression(forConstantValue: 3)
            style.addLayer(capaSel)

            let marcas = MLNShapeSource(identifier: "marcas", shape: nil, options: nil)
            style.addSource(marcas)
            let iconos = MLNSymbolStyleLayer(identifier: "capa-marcas", source: marcas)
            iconos.iconImageName = NSExpression(forKeyPath: "icono")
            iconos.iconAllowsOverlap = NSExpression(forConstantValue: true)
            iconos.iconIgnoresPlacement = NSExpression(forConstantValue: true)
            style.addLayer(iconos)

            estilo = style
            actualizar(mapa)
        }

        func mapView(_ mapa: MLNMapView, regionDidChangeAnimated animated: Bool) {
            let c = mapa.centerCoordinate
            padre.alMoverCentro?(Ubicacion(latitud: redondear(c.latitude), longitud: redondear(c.longitude)))
        }

        func actualizar(_ mapa: MLNMapView) {
            guard let estilo else { return }
            let marcas: [MLNPointFeature] = padre.marcas.map { m in
                let clave = "icono-\(m.icono ?? "-")-\(m.colorHex)-\(m.propio)"
                if estilo.image(forName: clave) == nil {
                    estilo.setImage(iconoMarcador(emoji: m.icono, colorHex: m.colorHex, propio: m.propio), forName: clave)
                }
                let p = MLNPointFeature()
                p.coordinate = CLLocationCoordinate2D(latitude: m.ubicacion.latitud, longitude: m.ubicacion.longitud)
                p.attributes = ["id": m.id, "icono": clave]
                return p
            }
            (estilo.source(withIdentifier: "marcas") as? MLNShapeSource)?.shape = MLNShapeCollectionFeature(shapes: marcas)
            (estilo.source(withIdentifier: "zonas") as? MLNShapeSource)?.shape = MLNShapeCollectionFeature(shapes: padre.zonas.map { z in
                let p = MLNPointFeature()
                p.coordinate = CLLocationCoordinate2D(latitude: z.ubicacion.latitud, longitude: z.ubicacion.longitud)
                p.attributes = ["reportes": z.reportes]
                return p
            })
            (estilo.source(withIdentifier: "usuario") as? MLNShapeSource)?.shape = Self.punto(padre.usuario)
            (estilo.source(withIdentifier: "seleccion") as? MLNShapeSource)?.shape = Self.punto(padre.seleccion)
            let centro = padre.centro ?? padre.seleccion
            if let centro, centro != ultimoCentro || padre.recentrar != ultimoRecentrar {
                ultimoCentro = centro
                ultimoRecentrar = padre.recentrar
                mapa.setCenter(CLLocationCoordinate2D(latitude: centro.latitud, longitude: centro.longitud), zoomLevel: padre.zoom, animated: true)
            }
        }

        private static func punto(_ u: Ubicacion?) -> MLNShapeCollectionFeature {
            MLNShapeCollectionFeature(shapes: [u].compactMap { $0 }.map { u in
                let p = MLNPointFeature()
                p.coordinate = CLLocationCoordinate2D(latitude: u.latitud, longitude: u.longitud)
                return p
            })
        }

        @objc func tocado(_ gesto: UITapGestureRecognizer) {
            guard let mapa = gesto.view as? MLNMapView else { return }
            let punto = gesto.location(in: mapa)
            if let tocar = padre.alTocarMarca,
               let id = mapa.visibleFeatures(at: punto, styleLayerIdentifiers: ["capa-marcas"]).first?.attribute(forKey: "id") as? String {
                tocar(id)
                return
            }
            guard let elegir = padre.alElegir else { return }
            let c = mapa.convert(punto, toCoordinateFrom: mapa)
            elegir(Ubicacion(latitud: redondear(c.latitude), longitud: redondear(c.longitude)))
        }
    }
}
