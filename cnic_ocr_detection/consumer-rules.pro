# ProGuard rules for cnic_ocr_detection
# These rules are automatically consumed by the host app.

# Keep the library's package structure and classes
-keep class pk.pitb.cnic_ocr_detection.** { *; }

# Keep Custom Views and their constructors to allow XML inflation
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep native methods if any are used in the module
-keepclasseswithmembernames class * {
    native <methods>;
}

# Note: ML Kit and TFLite usually bundle their own consumer rules.
# If you encounter issues with them, specific keep rules can be added here.
