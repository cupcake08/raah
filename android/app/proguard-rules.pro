# Raah has no reflection of its own: R8's defaults plus the libraries' bundled rules are enough.
# Strip debug and verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
