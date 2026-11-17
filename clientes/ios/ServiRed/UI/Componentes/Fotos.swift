import PhotosUI
import SwiftUI

/**
 Tomar una foto (cámara del sistema) o elegirla de la galería (PhotosPicker,
 sin permiso de acceso a toda la fototeca). Cada foto se prepara (enderezada
 y sin metadatos) antes de entregarla.
 */
struct SelectorFotos: View {
    let fotos: [String]
    let maximo: Int
    let preparador: PreparadorFotos
    let alAgregar: (String) -> Void
    let alQuitar: (String) -> Void
    var habilitado = true

    @State private var elegida: PhotosPickerItem?
    @State private var camara = false
    @State private var preparando = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Fotos (opcional, hasta \(maximo))").font(.subheadline.weight(.semibold))
            if !fotos.isEmpty {
                ScrollView(.horizontal) {
                    HStack {
                        ForEach(fotos, id: \.self) { nombre in
                            ZStack(alignment: .topTrailing) {
                                Miniatura(url: preparador.carpeta.ruta(nombre))
                                    .frame(width: 96, height: 96)
                                Button { alQuitar(nombre) } label: {
                                    Image(systemName: "xmark.circle.fill").font(.title3).symbolRenderingMode(.palette)
                                        .foregroundStyle(.white, .black.opacity(0.6))
                                }
                                .accessibilityLabel("Quitar foto")
                            }
                        }
                    }
                }
            }
            if preparando { ProgressView() }
            if fotos.count < maximo {
                HStack {
                    if UIImagePickerController.isSourceTypeAvailable(.camera) {
                        Button("Tomar foto") { camara = true }
                    }
                    PhotosPicker("Elegir de la galería", selection: $elegida, matching: .images)
                }
                .buttonStyle(.bordered)
                .disabled(!habilitado || preparando)
            }
        }
        .onChange(of: elegida) { _, item in
            guard let item else { return }
            elegida = nil
            preparando = true
            Task {
                let datos = try? await item.loadTransferable(type: Data.self)
                let preparador = preparador
                let nombre = await Task.detached { datos.flatMap { preparador.preparar(datos: $0) } }.value
                preparando = false
                nombre.map(alAgregar)
            }
        }
        .fullScreenCover(isPresented: $camara) {
            CamaraSistema { imagen in
                preparando = true
                let preparador = preparador
                Task {
                    let nombre = await Task.detached { preparador.preparar(imagen: imagen) }.value
                    preparando = false
                    nombre.map(alAgregar)
                }
            }
            .ignoresSafeArea()
        }
    }
}

struct Miniatura: View {
    let url: URL
    @State private var imagen: UIImage?

    var body: some View {
        Group {
            if let imagen {
                Image(uiImage: imagen).resizable().scaledToFill()
            } else {
                Color.secondary.opacity(0.2)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityLabel("Foto adjunta")
        .task(id: url) {
            let u = url
            imagen = await Task.detached { UIImage(contentsOfFile: u.path)?.preparingThumbnail(of: CGSize(width: 256, height: 256)) }.value
        }
    }
}

/// Cámara del sistema (UIImagePickerController).
struct CamaraSistema: UIViewControllerRepresentable {
    let alCapturar: (UIImage) -> Void
    @Environment(\.dismiss) private var cerrar

    func makeCoordinator() -> Coordinador { Coordinador(self) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let c = UIImagePickerController()
        c.sourceType = .camera
        c.delegate = context.coordinator
        return c
    }

    func updateUIViewController(_ controlador: UIImagePickerController, context: Context) {}

    final class Coordinador: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let padre: CamaraSistema
        init(_ padre: CamaraSistema) { self.padre = padre }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let imagen = info[.originalImage] as? UIImage { padre.alCapturar(imagen) }
            padre.cerrar()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { padre.cerrar() }
    }
}
