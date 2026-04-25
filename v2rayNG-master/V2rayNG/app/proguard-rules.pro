# ============================================================
#  ProGuard / R8 rules — Yum VPN (v2rayNG)
# ============================================================

# ── Giữ thông tin debug để đọc stack trace dễ hơn ──────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Giữ Exceptions & Annotations ───────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions


# ════════════════════════════════════════════════════════════
#  1. TENCENT MMKV
# ════════════════════════════════════════════════════════════
-keep class com.tencent.mmkv.** { *; }


# ════════════════════════════════════════════════════════════
#  2. GSON — giữ các DTO/data class dùng để parse JSON
# ════════════════════════════════════════════════════════════
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.v2ray.ang.dto.** { *; }


# ════════════════════════════════════════════════════════════
#  3. V2RAY CORE & JNI
# ════════════════════════════════════════════════════════════
-keep class com.v2ray.** { *; }
-keep class go.** { *; }
-keep class libv2ray.** { *; }


# ════════════════════════════════════════════════════════════
#  4. OKHTTP
# ════════════════════════════════════════════════════════════
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }


# ════════════════════════════════════════════════════════════
#  5. KOTLIN COROUTINES
# ════════════════════════════════════════════════════════════
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**


# ════════════════════════════════════════════════════════════
#  6. ANDROIDX / JETPACK
# ════════════════════════════════════════════════════════════
-keep class androidx.lifecycle.** { *; }
-keep class androidx.work.** { *; }
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}


# ════════════════════════════════════════════════════════════
#  7. ANDROID COMPONENTS
# ════════════════════════════════════════════════════════════
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application


# ════════════════════════════════════════════════════════════
#  8. OTP / COMMONS-CODEC
# ════════════════════════════════════════════════════════════
-keep class org.apache.commons.codec.** { *; }
-dontwarn org.apache.commons.codec.**


# ════════════════════════════════════════════════════════════
#  9. ENUM
# ════════════════════════════════════════════════════════════
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# ════════════════════════════════════════════════════════════
#  10. PARCELABLE & SERIALIZABLE
# ════════════════════════════════════════════════════════════
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}


# ════════════════════════════════════════════════════════════
#  11. SUPPRESS WARNINGS
# ════════════════════════════════════════════════════════════
-dontwarn com.google.android.material.**
-dontwarn androidx.**
-dontwarn kotlin.**
-dontwarn java.lang.invoke.**
