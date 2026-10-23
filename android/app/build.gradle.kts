
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Penandatanganan APK (agar bisa di-upgrade di tempat, tanpa uninstall).
//
// `keystore.properties` di root project (android/) menyimpan kunci penandatangan:
// - Di repo ini: keystore bersama (committed) supaya SEMUA build CI memakai kunci
//   yang sama. Sebelumnya build debug CI memakai debug keystore acak yang berbeda
//   tiap run → tanda tangan tak cocok → Android menolak update (wajib uninstall).
// - Di CI rilis (tag v*): file ini ditulis ulang dari GitHub Secrets (kunci rilis).
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasSigning = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.arkacode.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arka.server"
        minSdk = 26
        targetSdk = 35
        // Naik tiap build CI (GITHUB_RUN_NUMBER) → update dikenali sistem.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.2"
    }

    signingConfigs {
        if (hasSigning) {
            create("arkacode") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasSigning) signingConfig = signingConfigs.getByName("arkacode")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("arkacode")
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

    // Binary proot harus benar-benar diekstrak ke nativeLibraryDir supaya bisa
    // dieksekusi: sejak Android 10 file di folder data aplikasi tidak boleh
    // di-exec (SELinux), sedangkan nativeLibraryDir boleh.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Hasil task downloadProotBinaries (libproot.so, libtalloc.so, ...).
    sourceSets.getByName("main") {
        jniLibs.srcDir(layout.buildDirectory.dir("generated/prootJniLibs"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)
    // Ekstraksi tar.gz rootfs Alpine (jalur hot: runtime DistroManager).
    implementation(libs.commons.compress)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------
// Proot binaries (M8 — distro Alpine untuk run_command)
//
// Android tanpa root tidak boleh mengeksekusi file dari folder data aplikasi,
// jadi binary proot harus ada di dalam APK sebagai jniLibs. Task di bawah
// mengunduh paket Termux yang sudah terbukti jalan di Android (proot + talloc +
// libandroid-shmem), membongkarnya, lalu menaruh hasilnya di
// build/generated/prootJniLibs/<abi>/ sebagai lib*.so.
//
// Kalau unduhan gagal (mis. offline), build TETAP jalan: APK dibuat tanpa
// dukungan proot, dan run_command tidak akan berjalan sampai binary proot tersedia.
// Lewati total dengan: ./gradlew assembleDebug -Pproot.skip=true
// ---------------------------------------------------------------------------

/** Lewati unduhan binary proot: `./gradlew assembleDebug -Pproot.skip=true`. */
val prootSkip = (project.findProperty("proot.skip") as String?)?.toBoolean() ?: false

/**
 * Izinkan build lanjut meski binary proot gagal disiapkan (mis. build offline):
 * `-Pproot.allowMissing=true`. Default-nya build GAGAL — APK tanpa proot
 * sebelumnya terbit diam-diam dan menampakkan diri di runtime sebagai error
 * "Distro belum siap" yang menyesatkan.
 */
val prootAllowMissing = (project.findProperty("proot.allowMissing") as String?)?.toBoolean() ?: false

val downloadProotBinaries = tasks.register("downloadProotBinaries") {
    group = "arka"
    description = "Mengunduh binary proot Android (paket Termux) ke jniLibs agar run_command bisa memakai distro Alpine."
    val outputDir = layout.buildDirectory.dir("generated/prootJniLibs").get().asFile
    val cacheDir = layout.buildDirectory.dir("proot-cache").get().asFile
    outputs.dir(outputDir)
    inputs.property("prootVersion", arka.ProotBinaries.VERSIONS)

    onlyIf { !prootSkip }

    doLast {
        val ready = arka.ProotBinaries.prepare(outputDir, cacheDir) { message -> logger.lifecycle(message) }
        if (ready == 0) {
            val message =
                "[proot] Tidak ada binary proot yang berhasil disiapkan (offline / versi pinned 404 dari mirror)."
            if (prootAllowMissing) {
                logger.warn(
                    "$message\n" +
                        "         APK tetap dibangun (proot.allowMissing=true), " +
                        "tapi engine tidak akan bisa jalan di perangkat.",
                )
            } else {
                throw GradleException(
                    "$message\n" +
                        "         Build dihentikan agar APK tanpa proot tidak terbit diam-diam.\n" +
                        "         Perbaiki: jalankan ulang dengan jaringan, atau pakai " +
                        "-Pproot.allowMissing=true (build offline) / -Pproot.skip=true (tanpa distro).",
                )
            }
        } else {
            logger.lifecycle("[proot] Siap untuk $ready ABI di ${outputDir.absolutePath}")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadProotBinaries)
}

// Task TTS installer dari Arkacode dipangkas: app host ini tanpa UI apapun.
