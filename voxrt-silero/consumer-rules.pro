# ProGuard / R8 rules consumed by apps that depend on voxrt-silero.

# JNI entrypoints — referenced from libvoxrt_silero.so by symbol name.
# R8 must not rename or strip them.
-keep class com.voxrt.silero.VoxrtNative {
    public static *;
}

-keep class com.voxrt.silero.VoxrtNative$Companion {
    public *;
}
