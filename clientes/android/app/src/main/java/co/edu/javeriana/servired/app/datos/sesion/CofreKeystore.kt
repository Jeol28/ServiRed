package co.edu.javeriana.servired.app.datos.sesion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cofre con AES-256-GCM y la clave en el Android Keystore (no exportable).
 * Los datos cifrados se guardan en preferencias privadas, excluidas del
 * respaldo en la nube (reglas_extraccion.xml).
 */
class CofreKeystore(contexto: Context) : CofreTokens {
    private val preferencias = contexto.getSharedPreferences("servired.sesion", Context.MODE_PRIVATE)

    override fun leer(): String? = descifrar(preferencias.getString(REFRESCO, null))
    override fun guardar(refresco: String) = preferencias.edit { putString(REFRESCO, cifrar(refresco)) }
    override fun borrarRefresco() = preferencias.edit { remove(REFRESCO) }
    override fun borrarTodo() = preferencias.edit { clear() }
    override fun leerCuenta(): String? = descifrar(preferencias.getString(CUENTA, null))
    override fun guardarCuenta(cuenta: String) = preferencias.edit { putString(CUENTA, cifrar(cuenta)) }

    private fun clave(): SecretKey {
        val almacen = KeyStore.getInstance(PROVEEDOR).apply { load(null) }
        (almacen.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEEDOR)
        generador.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generador.generateKey()
    }

    private fun cifrar(texto: String): String {
        val cifrador = Cipher.getInstance(TRANSFORMACION).apply { init(Cipher.ENCRYPT_MODE, clave()) }
        val datos = cifrador.iv + cifrador.doFinal(texto.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(datos, Base64.NO_WRAP)
    }

    private fun descifrar(guardado: String?): String? {
        if (guardado == null) return null
        return runCatching {
            val datos = Base64.decode(guardado, Base64.NO_WRAP)
            val cifrador = Cipher.getInstance(TRANSFORMACION)
            cifrador.init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, datos, 0, IV))
            String(cifrador.doFinal(datos, IV, datos.size - IV), Charsets.UTF_8)
        }.getOrNull() // clave perdida (p. ej. restauración del sistema): como si no hubiera sesión
    }

    private companion object {
        const val PROVEEDOR = "AndroidKeyStore"
        const val ALIAS = "servired.sesion"
        const val TRANSFORMACION = "AES/GCM/NoPadding"
        const val IV = 12
        const val REFRESCO = "refresco"
        const val CUENTA = "cuenta"
    }
}
