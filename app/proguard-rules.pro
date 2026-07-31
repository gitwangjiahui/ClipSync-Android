# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# 保留 kotlinx-serialization 相关类
-keep,includedescriptorclasses class com.clipsync.**$$serializer { *; }
-keepclassmembers class com.clipsync.** {
    *** Companion;
}
-keepclasseswithmembers class com.clipsync.** {
    kotlinx.serialization.KSerializer serializer(...);
}
