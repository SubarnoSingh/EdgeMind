package com.example.EdgeMemo.testing

import java.io.File

object TestNativeLoader {
    private const val LIB_PROPERTY = "edgememo.qdrant.lib"
    private const val LIB_RELATIVE_PATH = "build/generated/native-libs/host/libedgememo_qdrant.so"

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.load(nativeLibraryPath())
            loaded = true
        }
    }

    fun nativeLibraryPath(): String {
        System.getProperty(LIB_PROPERTY)?.let { configured ->
            if (File(configured).isFile) return configured
        }
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            if (dir == null) return@repeat
            val candidate = File(dir, LIB_RELATIVE_PATH)
            if (candidate.isFile) return candidate.absolutePath
            dir = dir!!.parentFile
        }
        throw IllegalStateException("native library not found relative to ${System.getProperty("user.dir")}")
    }
}