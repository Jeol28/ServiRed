package co.edu.javeriana.servired.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import co.edu.javeriana.servired.app.ServiRedApp

class ActividadPrincipal : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val contenedor = (application as ServiRedApp).contenedor
        setContent { AppServiRed(contenedor) }
    }
}
