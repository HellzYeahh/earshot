# Shizuku uses reflection on hidden Android APIs through its own stubs.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.**
-dontwarn moe.shizuku.**

# Shizuku instantiates the codec user service by class name in its own process
# (dev.earshot:codec), trying the (Context) constructor first, then the no-arg one.
-keep class dev.earshot.codec.shizuku.CodecUserService { public <init>(...); }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class dev.earshot.** {
    kotlinx.serialization.KSerializer serializer(...);
}
