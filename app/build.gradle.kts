import java.util.Properties

plugins {
    id("com.android.application")
    jacoco
}

// Release signing comes from keystore.properties locally or RELEASE_* env vars in CI.
// With neither set, release builds fall back to the debug key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotEmpty() } ?: keystoreProps.getProperty(prop)
val releaseStoreFile = signingValue("storeFile", "RELEASE_KEYSTORE_FILE")

android {
    namespace = "com.bkacjios.babblebridge"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bkacjios.babblebridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core:1.19.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("androidx.test:core:1.7.0")
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}
