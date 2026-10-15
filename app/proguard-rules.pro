# R8 rules for the release build. Firebase, Google Mobile Ads / UMP, Play Billing, Health Connect,
# Credential Manager and kotlinx.coroutines ship their own consumer rules; these cover what is
# specific to this app.

# Keep line numbers so Play Console stack traces stay readable (Play deobfuscates them with the R8
# mapping that the app bundle embeds).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Band protocol messages (src/main/proto, protobuf-javalite): the lite runtime resolves message fields
# by name through reflection. Firebase's protolite-well-known-types has the same rule; it is repeated
# here so the band protocol does not depend on it.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Enum constant names are persisted (database, settings keys, Health Connect sync tokens, cloud backup)
# and parsed back with valueOf(): never rename them, so data written by one release reads in the next.
-keepclassmembers enum com.enjoyyourtime.globalfitness.** {
    <fields>;
}

# Credential Manager finds its Play services provider by reflection (rule from the Credential Manager
# documentation; the library itself only keeps the provider class).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
    *;
}
