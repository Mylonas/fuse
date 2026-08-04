import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from either a local keystore.properties (storeFile /
// storePassword / keyAlias / keyPassword) or, in CI, Gradle properties supplied
// from secrets. Without either, release builds fall back to the debug key so the
// project still builds for anyone who clones it — see -PrequireRelease below.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
/** A Gradle property, or null when unset/blank. CI supplies these from secrets
 *  via ORG_GRADLE_PROJECT_* env vars, so nothing lands on a command line. */
fun secretProp(name: String): String? =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

val storeFilePath = secretProp("KEYSTORE_FILE") ?: keystoreProps.getProperty("storeFile")
val storePass = secretProp("KEYSTORE_PASSWORD") ?: keystoreProps.getProperty("storePassword")
val keyAliasName = secretProp("KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
val keyPass = secretProp("KEY_PASSWORD") ?: keystoreProps.getProperty("keyPassword")

val resolvedKeystore: File? = storeFilePath?.let {
    // NB: in the Gradle Kotlin DSL `java` is the JavaPluginExtension accessor,
    // so java.io.File has to be imported rather than fully qualified here.
    val f = File(it)
    if (f.isAbsolute) f else rootProject.file(it)
}
val canSignRelease = resolvedKeystore?.exists() == true &&
    !storePass.isNullOrBlank() && !keyAliasName.isNullOrBlank() && !keyPass.isNullOrBlank()

// Real ad unit IDs never live in git. Put them in your USER-level
// ~/.gradle/gradle.properties, or pass -PADMOB_APP_ID=... on the command line,
// or set them as CI secrets. Without them the build uses Google's official TEST
// ids, which are safe to run anywhere and are what debug builds always get.
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"
val admobAppId = (project.findProperty("ADMOB_APP_ID") as String?) ?: testAdmobAppId
val interstitialId = (project.findProperty("ADMOB_INTERSTITIAL_ID") as String?) ?: testInterstitialId

// Set -PrequireRelease=true for builds intended for Play. It turns the two
// silent failure modes into loud ones: an AAB signed with the debug key, which
// Play rejects on upload, and a production build still serving Google's test
// ads, which earns nothing.
if (project.hasProperty("requireRelease")) {
    if (!canSignRelease) {
        throw GradleException(
            "requireRelease is set but no signing key was supplied. This build " +
                "would be signed with the debug key and Play would reject it."
        )
    }
    if (admobAppId == testAdmobAppId || interstitialId == testInterstitialId) {
        throw GradleException(
            "requireRelease is set but the AdMob ids are still Google's test ids. " +
                "This build would ship test ads to production and earn nothing."
        )
    }
}

android {
    namespace = "com.mikmy.fuse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mikmy.fuse"
        minSdk = 26
        targetSdk = 35
        // Play needs a higher versionCode on every upload; CI passes it in.
        versionCode = secretProp("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = secretProp("VERSION_NAME") ?: "1.0.0"
        resourceConfigurations += setOf("en")

        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_APP_ID", "\"$admobAppId\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$interstitialId\"")
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = resolvedKeystore
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
                // A .p12 can be made with openssl alone, no JDK required.
                val n = resolvedKeystore!!.name.lowercase()
                if (n.endsWith(".p12") || n.endsWith(".pfx")) storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (canSignRelease) {
                signingConfigs.getByName("release")
            } else {
                // Sideloadable, but Play rejects it. -PrequireRelease guards this.
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            // Serving yourself live ads gets AdMob accounts suspended, so a
            // debug build can only ever show test ads.
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("String", "ADMOB_APP_ID", "\"$testAdmobAppId\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$testInterstitialId\"")
        }
    }

    buildFeatures {
        buildConfig = true   // only used for the debug-only fps log
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        htmlReport = true
        textReport = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AdMob. The GMA Next-Gen SDK — Google put the classic play-services-ads
    // into maintenance mode in January 2026 and recommends this for new apps.
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.3.1")
    // Consent for EEA/UK users. Required by Google's EU user consent policy.
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    testImplementation("junit:junit:4.13.2")
}
