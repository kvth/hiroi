import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Everything that doesn't need Android: dictionaries, lookups, Japanese, the results' HTML, Anki
// card formatting. Plain Kotlin on the JVM, so the app (and any later desktop app) shares it.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Android has org.json built in, so core only compiles against it; whoever runs core elsewhere
    // brings an org.json of their own. This org.json has a few methods Android's lacks
    // (JSONArray isn't Iterable on Android, for one): stick to the ones Android documents.
    compileOnly("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
