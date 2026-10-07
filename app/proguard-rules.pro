# Room instantiates generated *_Impl databases by name. WorkManager (pulled in by
# Glance for widget updates) has one; R8 full mode otherwise strips its constructor and
# the app dies at startup with "Failed to create an instance of WorkDatabase".
-keep class * extends androidx.room.RoomDatabase { <init>(); }
