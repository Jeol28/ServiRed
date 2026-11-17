# kotlinx.serialization: conservar los serializadores generados de los DTO.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep,includedescriptorclasses class co.edu.javeriana.servired.app.datos.remoto.**$$serializer { *; }
-keepclassmembers class co.edu.javeriana.servired.app.datos.remoto.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit con funciones suspend
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
