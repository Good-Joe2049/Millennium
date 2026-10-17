-keep,allowoptimization class com.millennium.app.ModuleMain {
    public <init>();
    public <methods>;
}

# Dhizuku's unused no-argument init() references this hidden platform class.
# Millennium always passes Steam's application context to init(Context).
-dontwarn android.app.ActivityThread
