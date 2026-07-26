# NewPipe Extractor evaluates YouTube's player JavaScript with Rhino.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Rhino exposes optional desktop-only integration points. Plyvanta calls the
# Android-compatible Rhino APIs directly, so these unavailable JDK packages are
# never reached on device.
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**

# Preserve the tiny model layer used from background callbacks.
-keepattributes Signature,InnerClasses,EnclosingMethod
