import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional release signing. Drop a keystore.properties next to settings.gradle.kts
// with storeFile / storePassword / keyAlias / keyPassword and release builds get
// signed properly; without it they fall back to the debug key so the project
// always builds for anyone who clones it.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists()

// Real ad unit IDs never live in git. Put them in your USER-level
// ~/.gradle/gradle.properties, or pass -PADMOB_APP_ID=... on the command line,
// or set them as CI secrets. Without them the build uses Google's official TEST
// ids, which are safe to run anywhere and are what debug builds always get.
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"
val admobAppId = (project.findProperty("ADMOB_APP_ID") as String?) ?: testAdmobAppId
val interstitialId = (project.findProperty("ADMOB_INTERSTITIAL_ID") as String?) ?: testInterstitialId

android {
    namespace = "com.mikmy.fuse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mikmy.fuse"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += setOf("en")

        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_APP_ID", "\"$admobAppId\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$interstitialId\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
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
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
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
