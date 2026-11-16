# Rules for the code that the app and its libraries do not call from Kotlin
# or Java, so that R8 keeps all of it.
#
# R8 spends most of its memory on full optimisation. Rewriting bytecode that
# way is what breaks reflective libraries, and the size that matters here is
# removing what is unused, not inlining what is left.
-dontoptimize

# WebRTC: peers and native code call back into these by name.
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }

# libsignal: the JNI layer looks classes up by name.
-keep class org.signal.libsignal.** { *; }

# SqlCipher: loaded by name and reached through native code.
-keep class net.zetetic.database.** { *; }

# UnifiedPush distributors are found and started by other apps.
-keep class org.unifiedpush.android.connector.** { *; }

# Optional libraries the above mention but the app does not include.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.eclipse.jetty.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.element.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
