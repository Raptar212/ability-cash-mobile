plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.cashbook"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.cashbook"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.6.0"
    }

    // Ключ вне репо: ~/.gradle/gradle.properties или ORG_GRADLE_PROJECT_cashbookStoreFile и т.д.
    // Нет свойств — release собирается неподписанным.
    val storeFilePath = providers.gradleProperty("cashbookStoreFile").orNull
    signingConfigs {
        if (storeFilePath != null) create("release") {
            storeFile = file(storeFilePath)
            storePassword = providers.gradleProperty("cashbookStorePassword").get()
            keyAlias = providers.gradleProperty("cashbookKeyAlias").get()
            keyPassword = providers.gradleProperty("cashbookKeyPassword").get()
        }
    }

    buildTypes {
        debug {
            // Отладочная сборка ставится рядом с рабочей и не трогает её базу.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
