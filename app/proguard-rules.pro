# -----------------------------------------------------------------------------
# Leaf Lite — R8 rules (full mode).
#
# The app uses no reflection of its own: no JSON mappers, no DI, no
# serialization, no ViewBinding reflection. Everything below is either a
# correctness guard for XML inflation (which AGP already handles through the
# generated aapt_rules.pro) or noise suppression.
# -----------------------------------------------------------------------------

# --- noise suppression --------------------------------------------------------
-dontwarn org.xmlpull.v1.**
-dontwarn kotlinx.coroutines.internal.**
-dontwarn org.slf4j.**
-dontnote kotlinx.coroutines.**

# --- XML pull parsing ---------------------------------------------------------
# android.util.Xml instantiates its own parser implementation, but keep the
# factory classes reachable in case a device/ROM falls back to the factory path.
-keep class org.xmlpull.v1.XmlPullParserFactory { *; }
-keepclassmembers class org.xmlpull.v1.** { *; }

# --- coroutines ---------------------------------------------------------------
# Dispatchers are looked up reflectively by the debug/probe machinery only;
# keep the entry points R8 cannot prove are used.
-keepclassmembers class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlin.coroutines.Continuation { *; }
-keepclassmembers,includedescriptorclasses class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- AppCompat / framework constructors reached from XML ----------------------
# AGP generates these for inflate-only classes; kept explicitly for safety.
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# --- logging ------------------------------------------------------------------
# Debug/verbose logging is compiled out of the release binary.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
