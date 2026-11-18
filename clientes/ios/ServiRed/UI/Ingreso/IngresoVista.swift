import SwiftUI

struct IngresoVista: View {
    @State var modelo: IngresoModelo
    let alDenunciar: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("SERVI-RED").font(.largeTitle.bold()).foregroundStyle(Color.accentColor)
                Text(modelo.registro
                     ? "Con su cuenta puede reportar cortes, confirmar los de sus vecinos y avisar cuando vuelva el servicio."
                     : "Reporte los cortes de energía, gas, agua, alcantarillado y aseo, y siga su atención.")
                Text(modelo.registro ? "Crear cuenta" : "Ingresar").font(.title2.weight(.semibold)).padding(.top, 8)
                TextField("Correo", text: $modelo.correo)
                    .textContentType(.username).keyboardType(.emailAddress).textInputAutocapitalization(.never)
                    .autocorrectionDisabled().textFieldStyle(.roundedBorder).accessibilityIdentifier("correo")
                SecureField("Clave", text: $modelo.clave)
                    .textContentType(.password).textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("clave")
                if modelo.registro {
                    Text("Entre 10 y 64 caracteres.").font(.caption).foregroundStyle(.secondary)
                    TextField("Nombre (opcional)", text: $modelo.nombre).textContentType(.name).textFieldStyle(.roundedBorder)
                    Toggle(isOn: $modelo.aceptaTratamiento) {
                        Text("Autorizo el tratamiento de mis datos personales para gestionar mis reportes (Ley 1581 de 2012). Puedo eliminar mi cuenta cuando quiera.")
                            .font(.footnote)
                    }
                    .accessibilityIdentifier("acepta")
                }
                Aviso(texto: modelo.error)
                Button {
                    Task { await modelo.enviar() }
                } label: {
                    Text(modelo.cargando ? "Un momento…" : modelo.registro ? "Crear cuenta" : "Ingresar").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).controlSize(.large).disabled(modelo.cargando)
                .accessibilityIdentifier("enviar")
                Button(modelo.registro ? "Ya tengo cuenta: ingresar" : "No tengo cuenta: registrarme") { modelo.alternarModo() }
                    .frame(maxWidth: .infinity)
                Divider()
                Text("¿Sabe de una conexión ilegal o un fraude? Puede denunciarlo sin cuenta y sin dejar ningún dato suyo.")
                    .font(.callout)
                Button("Hacer una denuncia anónima", action: alDenunciar).buttonStyle(.bordered).frame(maxWidth: .infinity)
            }
            .padding(24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
    }
}
