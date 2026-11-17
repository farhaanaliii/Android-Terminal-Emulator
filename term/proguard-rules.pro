# Classes the native libraries bind by name at JNI_OnLoad time
# (registerNativeMethods in term/src/main/jni/common.cpp and the
# Java_jackpal_androidterm_* symbols in libtermexec). R8 must neither
# strip nor rename them: FileCompat$Api8OrEarlier is unreachable from
# Java, so without this keep it is shrunk away and
# libjackpal-androidterm5 fails to load with JNI_ERR.
-keep class jackpal.androidterm.Exec { native <methods>; }
-keep class jackpal.androidterm.TermExec { native <methods>; }
-keep class jackpal.androidterm.compat.FileCompat$Api8OrEarlier { *; }
