plugins {
    id("com.android.application")
    id("com.chaquo.python")
}


val buildSkydnirNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build Skydnir native payloads from TermPort vendored Skydnir sources."
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-skydnir-native.sh")
}

val syncSkydnirDaemon by tasks.registering(Copy::class) {
    group = "build"
    description = "Stage the vendored Skydnir daemon source into APK assets."
    from(project.file("src/main/skydnir-daemon/skydnird"))
    into(project.file("src/main/assets/skydnir"))
}

val verifySkydnirNativeFresh by tasks.registering {
    group = "verification"
    description = "Fail APK builds when packaged Skydnir payloads are missing or older than their sources."
    dependsOn(buildSkydnirNative, syncSkydnirDaemon)

    fun requireFresh(output: File, vararg inputs: File) {
        if (!output.isFile) throw GradleException("Missing generated Skydnir payload: " + output)
        inputs.forEach { input ->
            if (!input.isFile) throw GradleException("Missing Skydnir source: " + input)
            if (output.lastModified() < input.lastModified()) {
                throw GradleException("Stale Skydnir payload: " + output + " is older than " + input)
            }
        }
    }

    fun requireSameBytes(output: File, input: File) {
        if (!output.isFile) throw GradleException("Missing generated Skydnir asset: " + output)
        if (!input.isFile) throw GradleException("Missing Skydnir source: " + input)
        if (!output.readBytes().contentEquals(input.readBytes())) {
            throw GradleException("Stale Skydnir asset: " + output + " differs from " + input)
        }
    }

    doLast {
        val sourceRoot = project.file("src/main/skydnir-native")
        val jni = project.file("src/main/jniLibs/arm64-v8a")
        val gpuAbi = sourceRoot.resolve("cpp/skydnir_gpu_abi.h")
        val gpuContainerAbi = sourceRoot.resolve("gpu/skydnir_gpu_abi.h")

        requireFresh(jni.resolve("libskydnirdirect.so"), sourceRoot.resolve("cpp/skydnir_direct_exec.c"))
        requireFresh(jni.resolve("libskydnirgpuexecutor.so"), sourceRoot.resolve("cpp/skydnir_gpu_executor.c"), sourceRoot.resolve("cpp/skydnir_q4k_safe_spv.inc"), gpuAbi)
        requireFresh(jni.resolve("libskydnirmediaexecutor.so"), sourceRoot.resolve("cpp/skydnir_media_executor.c"))
        requireFresh(jni.resolve("libcow.so"), sourceRoot.resolve("overlay/skydnir_cow.c"))
        requireFresh(jni.resolve("libskydnirgpushim.so"), sourceRoot.resolve("gpu/skydnir_gpu_shim.c"), gpuContainerAbi)
        requireFresh(jni.resolve("libskydnirvulkanicd.so"), sourceRoot.resolve("gpu/skydnir_vulkan_icd.c"), gpuContainerAbi)
        requireFresh(jni.resolve("libskydniropenclicd.so"), sourceRoot.resolve("gpu/skydnir_opencl_icd.c"), gpuContainerAbi)
        val fdroidNoCrane = (System.getenv("SKYDNIR_FDROID_NO_CRANE") ?: "0") != "0"
        if (fdroidNoCrane) {
            if (jni.resolve("libcrane.so").exists()) {
                throw GradleException("F-Droid mode must not package prebuilt crane payload")
            }
        } else {
            requireFresh(jni.resolve("libcrane.so"), sourceRoot.resolve("crane"))
        }
        requireFresh(jni.resolve("libpdocker-ld-linux-aarch64.so"), jni.resolve("libpdocker-ld-linux-aarch64.so"))
        requireSameBytes(project.file("src/main/assets/skydnir/skydnird"), project.file("src/main/skydnir-daemon/skydnird"))
    }
}

android {
    namespace = "io.github.ryo100794.termport"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.ryo100794.termport"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/*.so")
        }
    }
}


tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifySkydnirNativeFresh)
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pyc { src = false }
    }
}
