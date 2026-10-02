# The JNI bridge looks these up by name; renaming them breaks the native side.
-keep class com.candy.vpn.core.CandyController { *; }
-keep class com.candy.vpn.core.CandyNative { *; }
