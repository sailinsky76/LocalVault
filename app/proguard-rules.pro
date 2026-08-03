# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class cn.localvault.app.core.vault.** {
    *** Companion;
}
-keepclasseswithmembers class cn.localvault.app.core.vault.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Argon2Kt JNI
-keep class com.lambdapioneer.argon2kt.** { *; }
