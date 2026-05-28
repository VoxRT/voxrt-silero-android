# ProGuard / R8 rules consumed by apps that depend on voxrt-silero.
#
# Two failure modes we're defending against in any consumer build
# with `isMinifyEnabled = true`:
#
#   1. R8 renames `com.voxrt.silero.VoxrtNative` to something like
#      `com.voxrt.silero.a`. JNI lookups from `libvoxrt_silero.so`
#      use the fully-qualified symbol name
#      (`Java_com_voxrt_silero_VoxrtNative_create`) and throw
#      `UnsatisfiedLinkError` at the first native call.
#
#   2. R8 strips or renames an `external fun` because the Kotlin
#      bytecode marks it as instance-method (the singleton receiver
#      is the implicit `this`), and R8 can't see through native-side
#      callers. Same outcome — silent failure at runtime.
#
# `VoxrtNative` is a Kotlin `object`, so in JVM bytecode it is:
#
#   public final class com.voxrt.silero.VoxrtNative {
#       public static final com.voxrt.silero.VoxrtNative INSTANCE;
#       public final native long create(byte[]);
#       ...etc
#   }
#
# The pre-v0.1.2 rules kept only `public static *` — that matches
# the synthetic getINSTANCE() accessor but NOT the instance native
# methods. AGP's default proguard-android-optimize.txt happens to
# include `-keepclasseswithmembernames class * { native <methods>; }`
# which masked the bug in practice. Custom proguard configs that
# override the defaults would have crashed.

-keep class com.voxrt.silero.VoxrtNative {
    public static ** INSTANCE;
    public static <fields>;
    public <methods>;
    native <methods>;
}

# Defence in depth: any class that has native methods should keep
# them under their original names so JNI resolution still works.
# Default Android proguard rules already include this, but pinning
# it here makes the contract explicit and survives a consumer who
# overrides the default rule set.
-keepclasseswithmembernames class * {
    native <methods>;
}

# `VoxrtSileroVadEngine` is the entry point consumers touch. Keep
# class name + public methods so call sites
# (`engine.processPcm(...)`, `engine.reset()`, etc.) resolve after
# R8.
-keep class com.voxrt.silero.VoxrtSileroVadEngine {
    public *;
}

# `VadEvent` is a Kotlin sealed/data shape returned to the consumer
# from `processPcm`. Its accessor names + enum constants must
# survive R8 so consumer pattern-matching (`event.kind`,
# `VadEvent.Kind.SPEECH_START`, ...) resolves.
-keep class com.voxrt.silero.VadEvent {
    public *;
}
-keep class com.voxrt.silero.VadEvent$* {
    public *;
}

# `CpuAffinity` exposes the high-perf thread-pin helper consumers
# can use to stabilise RTF on big.LITTLE SoCs (per the public
# README — production code should call
# `CpuAffinity.applyToCurrentThread(...)`).
-keep class com.voxrt.silero.CpuAffinity {
    public *;
}
-keep class com.voxrt.silero.CpuAffinity$* {
    public *;
}
