import SwiftUI

struct MisReportesVista: View {
    @State var modelo: MisReportesModelo
    let alNuevo: () -> Void

    var body: some View {
        List {
            Section {
                Button(action: alNuevo) {
                    Label("Reportar un corte", systemImage: "plus.circle.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .accessibilityIdentifier("reportar")
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
            if modelo.sinConexion {
                Aviso(texto: "Sin conexión: ve los reportes guardados en el teléfono.", error: false)
            }
            Aviso(texto: modelo.error)
            if !modelo.pendientes.isEmpty {
                Section("Por enviar") {
                    ForEach(modelo.pendientes) { p in TarjetaPendiente(p: p) { Task { await modelo.descartar(p.idLocal) } } }
                }
            }
            Section {
                if modelo.reportes.isEmpty && modelo.pendientes.isEmpty && !modelo.actualizando {
                    Text("Aún no tiene reportes. Si se le fue un servicio, repórtelo con el botón de arriba.")
                        .foregroundStyle(.secondary)
                }
                ForEach(modelo.reportes) { r in
                    NavigationLink(value: Ruta.detalle(r.id)) { TarjetaReporte(r: r) }
                }
            }
        }
        .navigationTitle("Mis reportes")
        .refreshable { await modelo.actualizar() }
        .toolbar {
            // La barra inferior queda bajo la barra de pestañas flotante: el atajo va arriba.
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: alNuevo) { Image(systemName: "plus") }.accessibilityLabel("Reportar un corte")
            }
        }
        .task {
            await modelo.cargarLocal()
            await modelo.actualizar()
        }
    }
}

private struct TarjetaReporte: View {
    let r: Reporte

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(r.tipoServicio.etiqueta).font(.headline).foregroundStyle(r.tipoServicio.color)
                Etiqueta(texto: r.estado.etiqueta, color: r.estado.color)
            }
            Text(r.nombreMunicipio ?? r.codigoMunicipio).font(.subheadline)
            Text("Desde \(Formato.fechaHora(r.inicioEstimado))").font(.caption)
            if let d = r.duracionMinutos { Text("Duró \(Formato.duracion(d))").font(.caption) }
            if r.validacionCorte?.validado == true {
                Text("✓ Validado por la comunidad").font(.caption).foregroundStyle(Color.accentColor)
            }
        }
    }
}

private struct TarjetaPendiente: View {
    let p: ReportePendiente
    let alDescartar: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(p.datos.tipoServicio.etiqueta).font(.headline).foregroundStyle(p.datos.tipoServicio.color)
            Text("Desde \(Formato.fechaHora(p.datos.inicioEstimado))").font(.caption)
            switch p.estado {
            case .PENDIENTE: Text("Esperando conexión: se enviará automáticamente.").font(.caption).foregroundStyle(.secondary)
            case .ENVIANDO: Text("Enviando…").font(.caption).foregroundStyle(.secondary)
            case .FALLIDO:
                Text("No se pudo enviar: \(p.error ?? "revise los datos")").font(.caption).foregroundStyle(.red)
                Button("Descartar", role: .destructive, action: alDescartar).font(.caption)
            }
        }
    }
}
