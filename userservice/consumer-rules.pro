# Shizuku launches RecorderService by FQCN reflection inside app_process.
# R8 must not rename/strip the class, its no-arg constructor, or its public
# AIDL stub methods.
-keep class dev.lyo.callrec.userservice.RecorderService { *; }
-keep class dev.lyo.callrec.userservice.HiddenApiBootstrap { *; }

# AudioRecorderJob reflectively touches AudioRecord internals on some HALs.
-keep class dev.lyo.callrec.userservice.AudioRecorderJob { *; }

# AIDL-generated stubs.
-keep class dev.lyo.callrec.aidl.** { *; }

# HiddenApiBypass uses unsafe field setters; keep its surface intact.
-keep class org.lsposed.hiddenapibypass.** { *; }
