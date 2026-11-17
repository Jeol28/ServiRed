package co.edu.javeriana.servired.app.datos.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Copia local de un reporte del ciudadano (el JSON del contrato), para verlo sin conexión. */
@Entity(tableName = "reportes")
data class ReporteEntidad(
    @PrimaryKey val id: String,
    val json: String,
    val fechaRegistro: Long,
)

/** Reporte por enviar (outbox del teléfono). */
@Entity(tableName = "pendientes")
data class PendienteEntidad(
    @PrimaryKey(autoGenerate = true) val idLocal: Long = 0,
    val correlationId: String,
    /** NuevoReporteDto en JSON. */
    val json: String,
    /** Rutas de las fotos ya preparadas (sin EXIF), en JSON. */
    val fotos: String,
    val fotosEnviadas: Int = 0,
    val estado: String,
    val error: String? = null,
    val creadoEn: Long,
    val idReporte: String? = null,
)

@Dao
interface DaoReportes {
    @Query("SELECT * FROM reportes ORDER BY fechaRegistro DESC")
    fun observar(): Flow<List<ReporteEntidad>>

    @Upsert
    suspend fun guardar(reportes: List<ReporteEntidad>)

    @Query("DELETE FROM reportes")
    suspend fun borrarTodo()

    @Transaction
    suspend fun reemplazar(reportes: List<ReporteEntidad>) {
        borrarTodo()
        guardar(reportes)
    }
}

@Dao
interface DaoPendientes {
    @Query("SELECT * FROM pendientes ORDER BY creadoEn")
    fun observar(): Flow<List<PendienteEntidad>>

    @Query("SELECT * FROM pendientes WHERE estado != 'FALLIDO' ORDER BY creadoEn")
    suspend fun porEnviar(): List<PendienteEntidad>

    @Query("SELECT * FROM pendientes WHERE idLocal = :idLocal")
    suspend fun buscar(idLocal: Long): PendienteEntidad?

    @Insert
    suspend fun insertar(p: PendienteEntidad): Long

    @Update
    suspend fun actualizar(p: PendienteEntidad)

    @Query("DELETE FROM pendientes WHERE idLocal = :idLocal")
    suspend fun borrar(idLocal: Long)

    @Query("DELETE FROM pendientes")
    suspend fun borrarTodo()
}

@Database(entities = [ReporteEntidad::class, PendienteEntidad::class], version = 1, exportSchema = true)
abstract class BaseDatos : RoomDatabase() {
    abstract fun reportes(): DaoReportes
    abstract fun pendientes(): DaoPendientes
}
