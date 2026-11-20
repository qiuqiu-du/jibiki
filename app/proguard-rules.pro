# zstd-jni calls back into Java from native code.
-keep class com.github.luben.zstd.** { *; }

# Kuromoji reads its dictionary from Java resources and builds token classes by name.
-keep class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**
