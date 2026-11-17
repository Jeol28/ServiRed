import Foundation
import Security

/**
 Cofre en el Keychain. `AfterFirstUnlockThisDeviceOnly`: no se sincroniza con
 iCloud ni viaja a otro dispositivo en una copia de seguridad, y está
 disponible para el envío en segundo plano tras el primer desbloqueo.
 */
struct CofreKeychain: CofreTokens {
    private let servicio = "co.edu.javeriana.servired.sesion"

    func leerRefresco() -> String? { leer("refresco").flatMap { String(data: $0, encoding: .utf8) } }
    func guardarRefresco(_ refresco: String) { guardar("refresco", Data(refresco.utf8)) }
    func borrarRefresco() { borrar("refresco") }
    func leerCuenta() -> Data? { leer("cuenta") }
    func guardarCuenta(_ cuenta: Data) { guardar("cuenta", cuenta) }

    func borrarTodo() {
        borrar("refresco")
        borrar("cuenta")
    }

    private func base(_ cuenta: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: servicio, kSecAttrAccount as String: cuenta]
    }

    private func leer(_ cuenta: String) -> Data? {
        var consulta = base(cuenta)
        consulta[kSecReturnData as String] = true
        consulta[kSecMatchLimit as String] = kSecMatchLimitOne
        var resultado: AnyObject?
        return SecItemCopyMatching(consulta as CFDictionary, &resultado) == errSecSuccess ? resultado as? Data : nil
    }

    private func guardar(_ cuenta: String, _ datos: Data) {
        borrar(cuenta)
        var item = base(cuenta)
        item[kSecValueData as String] = datos
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }

    private func borrar(_ cuenta: String) {
        SecItemDelete(base(cuenta) as CFDictionary)
    }
}
