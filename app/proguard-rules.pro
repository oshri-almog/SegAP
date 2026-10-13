# HostapdCtl is a debugging tool started by class name:
#   CLASSPATH=<apk> app_process /system/bin com.oshri.multihotspot.root.HostapdCtl ...
-keep class com.oshri.multihotspot.root.HostapdCtl {
    public static void main(java.lang.String[]);
}

# Enum names are stored in the saved config (e.g. WifiMode via valueOf); keep them stable.
-keepclassmembers enum com.oshri.multihotspot.** {
    <fields>;
}

# Shizuku engine: runs in another process (Shizuku user service, or app_process for tests), started by class name.
-keep class com.oshri.multihotspot.shizuku.** { *; }
