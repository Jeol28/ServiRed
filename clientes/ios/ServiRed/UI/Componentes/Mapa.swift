import MapLibre
import SwiftUI

struct MarcaMapa: Equatable, Identifiable {
    var id: String
    var ubicacion: Ubicacion
    var colorHex: String
}

/// Estilo con teselas raster de OpenStreetMap y su atribución.
enum EstiloOSM {
    static let url: URL = {
        let json = """
        {"version":8,
         "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
           "tileSize":256,"maxzoom":19,"attribution":"© colaboradores de OpenStreetMap"}},
         "layers":[{"id":"osm","type":"raster","source":"osm"}]}
        """
        let u = URL.cachesDirectory.appending(path: "estilo-osm.json")
        try? Data(json.utf8).write(to: u)
        return u
    }()
}

/// Mapa MapLibre con OpenStreetMap: dibuja marcas circulares y, si se pasa
/// `alElegir`, permite marcar una ubicación tocando el mapa.
struct MapaOSM: UIViewRepresentable {
    var marcas: [MarcaMapa] = []
    var seleccion: Ubicacion?
    var centro: Ubicacion?
    var zoom: Double = 15
    var alElegir: ((Ubicacion) -> Void)?

    func makeCoordinator() -> Coordinador { Coordinador(self) }

    func makeUIView(context: Context) -> MLNMapView {
        let mapa = MLNMapView(frame: .zero, styleURL: EstiloOSM.url)
        mapa.delegate = context.coordinator
        mapa.allowsRotating = false
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

        init(_ padre: MapaOSM) { self.padre = padre }

        func mapView(_ mapa: MLNMapView, didFinishLoading style: MLNStyle) {
            let marcas = MLNShapeSource(identifier: "marcas", shape: nil, options: nil)
            style.addSource(marcas)
            let capa = MLNCircleStyleLayer(identifier: "capa-marcas", source: marcas)
            capa.circleRadius = NSExpression(forConstantValue: 9)
            capa.circleColor = NSExpression(mglJSONObject: ["to-color", ["get", "color"]])
            capa.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            capa.circleStrokeWidth = NSExpression(forConstantValue: 2)
            style.addLayer(capa)
            let seleccion = MLNShapeSource(identifier: "seleccion", shape: nil, options: nil)
            style.addSource(seleccion)
            let capaSel = MLNCircleStyleLayer(identifier: "capa-seleccion", source: seleccion)
            capaSel.circleRadius = NSExpression(forConstantValue: 11)
            capaSel.circleColor = NSExpression(forConstantValue: UIColor(red: 0.04, green: 0.33, blue: 0.58, alpha: 1))
            capaSel.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            capaSel.circleStrokeWidth = NSExpression(forConstantValue: 3)
            style.addLayer(capaSel)
            estilo = style
            actualizar(mapa)
        }

        func actualizar(_ mapa: MLNMapView) {
            guard let estilo else { return }
            (estilo.source(withIdentifier: "marcas") as? MLNShapeSource)?.shape = MLNShapeCollectionFeature(shapes: padre.marcas.map { m in
                let p = MLNPointFeature()
                p.coordinate = CLLocationCoordinate2D(latitude: m.ubicacion.latitud, longitude: m.ubicacion.longitud)
                p.attributes = ["id": m.id, "color": m.colorHex]
                return p
            })
            (estilo.source(withIdentifier: "seleccion") as? MLNShapeSource)?.shape = MLNShapeCollectionFeature(
                shapes: [padre.seleccion].compactMap { $0 }.map { u in
                    let p = MLNPointFeature()
                    p.coordinate = CLLocationCoordinate2D(latitude: u.latitud, longitude: u.longitud)
                    return p
                })
            let centro = padre.centro ?? padre.seleccion
            if let centro, centro != ultimoCentro {
                ultimoCentro = centro
                mapa.setCenter(CLLocationCoordinate2D(latitude: centro.latitud, longitude: centro.longitud), zoomLevel: padre.zoom, animated: true)
            }
        }

        @objc func tocado(_ gesto: UITapGestureRecognizer) {
            guard let mapa = gesto.view as? MLNMapView, let elegir = padre.alElegir else { return }
            let c = mapa.convert(gesto.location(in: mapa), toCoordinateFrom: mapa)
            elegir(Ubicacion(latitud: redondear(c.latitude), longitud: redondear(c.longitude)))
        }
    }
}
