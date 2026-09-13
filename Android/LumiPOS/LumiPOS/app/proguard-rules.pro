# LumiPOS ProGuard / R8 rules

## Gson (backup export/import) - keep class names AND field names
-keep class com.lumipos.data.schema.** { *; }
-keep class com.lumipos.data.export.** { *; }

## kotlinx.serialization (type-safe navigation routes)
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**

## Hilt / Dagger
-dontwarn dagger.hilt.**