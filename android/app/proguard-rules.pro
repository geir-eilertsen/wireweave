# The tunnel library reaches its Go backend through JNI, so its native entry points must keep
# their names even when R8 renames everything else.
-keep class com.wireguard.android.backend.** { *; }
