# OpenCV specific ProGuard rules
# These rules ensure that the JNI interfaces and necessary classes are not stripped.

-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** {
    native <methods>;
}

# Keep the library loader classes
-keep class org.opencv.android.OpenCVLoader { *; }
-keep class org.opencv.android.StaticHelper { *; }

# If using Camera Bridge
-keep class org.opencv.android.JavaCameraView { *; }
-keep class org.opencv.android.JavaCamera2View { *; }
-keep class org.opencv.android.CameraBridgeViewBase { *; }
