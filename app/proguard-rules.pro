-keep class com.ownapps.app.data.db.entity.** { *; }

# Gson reflectively reads/writes these UIHider models; R8 must not rename or strip their fields
# or the persisted config JSON would no longer match the model.
-keep class com.ownapps.app.uihider.UiHiderConfig { *; }
-keep class com.ownapps.app.uihider.UiHiderScript { *; }

# The firewall reaches Shizuku's private Shizuku.newProcess by name via getDeclaredMethod, and
# casts the result to ShizukuRemoteProcess, so R8 must keep both untouched.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
