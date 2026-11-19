import SwiftUI

struct CuentaVista: View {
    @State var modelo: CuentaModelo
    @State private var confirmarEliminar = false

    var body: some View {
        Form {
            if let c = modelo.cuenta { Section { Text(c.correo).font(.headline) } }
            Aviso(texto: modelo.aviso, error: false)
            Aviso(texto: modelo.error)
            Section("Nombre") {
                TextField("Nombre", text: $modelo.nombre).textContentType(.name)
                Button("Guardar nombre") { Task { await modelo.guardarNombre() } }.disabled(modelo.ocupado)
            }
            Section {
                SecureField("Clave actual", text: $modelo.claveActual).textContentType(.password)
                SecureField("Clave nueva (10 a 64 caracteres)", text: $modelo.claveNueva).textContentType(.newPassword)
                Button("Cambiar clave") { Task { await modelo.cambiarClave() } }
                    .disabled(modelo.ocupado || modelo.claveActual.isEmpty || modelo.claveNueva.isEmpty)
            } header: {
                Text("Cambiar clave")
            } footer: {
                Text("Al cambiarla se cierran todas sus sesiones.")
            }
            Section {
                Button(modelo.enviandoPendientes ? "Enviando reportes pendientes…" : "Cerrar sesión") {
                    Task { await modelo.cerrarSesion() }
                }
                .disabled(modelo.ocupado)
                Button("Eliminar mi cuenta", role: .destructive) { confirmarEliminar = true }
                    .accessibilityIdentifier("eliminar-cuenta")
            } footer: {
                Text("Antes de cerrar sesión se envían los reportes pendientes. Si alguno no se puede enviar, la sesión sigue abierta para no perderlo. Al cerrarla se borran del teléfono sus reportes guardados.")
            }
        }
        .navigationTitle("Mi cuenta")
        .task { await modelo.cargar() }
        .alert("La sesión sigue abierta", isPresented: Binding(
            get: { modelo.cierreBloqueado != nil },
            set: { if !$0 { modelo.cierreBloqueado = nil } }
        )) {
            Button("Entendido", role: .cancel) { modelo.cierreBloqueado = nil }
        } message: {
            Text(modelo.cierreBloqueado ?? "")
        }
        .confirmationDialog("¿Eliminar su cuenta?", isPresented: $confirmarEliminar, titleVisibility: .visible) {
            Button("Eliminar", role: .destructive) { Task { await modelo.eliminarCuenta() } }
        } message: {
            Text("Se borran su correo y su nombre y se cierran sus sesiones. Sus reportes se conservan sin datos que lo identifiquen, porque sirven a los indicadores públicos. No se puede deshacer.")
        }
    }
}
