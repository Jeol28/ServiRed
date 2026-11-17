import SwiftUI

struct DetalleReporteVista: View {
    @State var modelo: DetalleReporteModelo
    let fotos: PreparadorFotos

    var body: some View {
        Group {
            if let r = modelo.reporte {
                contenido(r)
            } else if modelo.cargando {
                ProgressView()
            } else {
                Aviso(texto: modelo.error).padding()
            }
        }
        .navigationTitle("Reporte")
        .navigationBarTitleDisplayMode(.inline)
        .task { await modelo.cargar() }
    }

    private func contenido(_ r: Reporte) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text(r.tipoServicio.etiqueta).font(.title2.bold()).foregroundStyle(r.tipoServicio.color)
                    Etiqueta(texto: r.estado.etiqueta, color: r.estado.color)
                }
                Aviso(texto: modelo.aviso, error: false)
                Aviso(texto: modelo.error)
                VStack(spacing: 6) {
                    Fila("Municipio", r.nombreMunicipio ?? r.codigoMunicipio)
                    Fila("Afecta a", r.magnitud.etiqueta)
                    Fila("Inicio", Formato.fechaHora(r.inicioEstimado))
                    Fila("Reportado", Formato.fechaHora(r.fechaRegistro))
                    if let f = r.fechaRestablecimiento { Fila("Volvió", Formato.fechaHora(f)) }
                    if let d = r.duracionMinutos { Fila("Duración", Formato.duracion(d)) }
                    Fila("Validación del corte", conteo(r.validacionCorte))
                    if r.fechaRestablecimiento != nil { Fila("Validación del regreso", conteo(r.validacionRestablecimiento)) }
                    if let d = r.descripcion { Text(d).frame(maxWidth: .infinity, alignment: .leading) }
                }
                .padding().background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
                MapaOSM(marcas: [MarcaMapa(id: r.id, ubicacion: r.ubicacion, colorHex: r.tipoServicio.hex)], centro: r.ubicacion)
                    .frame(height: 200).clipShape(RoundedRectangle(cornerRadius: 12))
                if r.puedeRestablecer { restablecimiento(r) }
                Text("Historia").font(.headline)
                ForEach(Array(r.historia.enumerated()), id: \.offset) { _, h in
                    VStack(alignment: .leading) {
                        Text("\(h.estado.etiqueta) · \(Formato.fechaHora(h.fecha))").font(.subheadline.weight(.semibold))
                        if let d = h.detalle { Text(d).font(.callout) }
                    }
                }
                Divider()
                Text("Fotos").font(.headline)
                if r.evidencias.isEmpty { Text("Sin fotos.").foregroundStyle(.secondary) }
                ForEach(r.evidencias) { ev in
                    if let datos = modelo.fotos[ev.id], let imagen = UIImage(data: datos) {
                        Image(uiImage: imagen).resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 8))
                            .accessibilityLabel("Foto del reporte")
                    } else {
                        Button("Ver foto (\(max(1, ev.tamanoBytes / 1024)) KB)") { Task { await modelo.verFoto(ev.id) } }
                            .buttonStyle(.bordered)
                    }
                }
                if r.puedeAdjuntarFoto {
                    SelectorFotos(fotos: [], maximo: Reglas.maxFotos - r.evidencias.count, preparador: fotos,
                                  alAgregar: { nombre in Task { await modelo.adjuntar(nombre) } }, alQuitar: { _ in },
                                  habilitado: !modelo.ocupado)
                }
            }
            .padding()
        }
    }

    private func restablecimiento(_ r: Reporte) -> some View {
        let maximo = max(0, Int(Date().timeIntervalSince(r.inicioEstimado) / 60) - 1)
        return VStack(alignment: .leading, spacing: 12) {
            Text("¿Ya volvió el servicio?").font(.headline)
            SelectorHoraPasada(titulo: "¿Cuándo volvió?", minutos: $modelo.minutosRestablecido, maximo: maximo)
            TextField("Comentario (opcional)", text: $modelo.comentario).textFieldStyle(.roundedBorder)
            Button {
                Task { await modelo.restablecer() }
            } label: {
                Text("El servicio volvió").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).disabled(modelo.ocupado)
        }
        .padding().background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private func conteo(_ c: Conteo?) -> String {
        guard let c else { return "Sin validaciones" }
        if c.validado { return "Validado (\(c.confirmaciones) confirman, \(c.controversias) no)" }
        return "\(c.confirmaciones) confirman, \(c.controversias) no (faltan \(Reglas.confirmacionesParaValidar - c.netas))"
    }
}

private struct Fila: View {
    let etiqueta: String
    let valor: String
    init(_ etiqueta: String, _ valor: String) { self.etiqueta = etiqueta; self.valor = valor }

    var body: some View {
        HStack(alignment: .top) {
            Text(etiqueta).foregroundStyle(.secondary)
            Spacer()
            Text(valor).fontWeight(.medium).multilineTextAlignment(.trailing)
        }
    }
}
