package com.example.EdgeMemo.testing

import java.io.File

object TestNativeLoader {
    private const val LIB_PROPERTY = "edgememo.qdrant.lib"
    private val LIB_RELATIVE_PATH = "build/generated/native-libs/host/" + System.mapLibraryName("edgememo_qdrant")

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val path = nativeLibraryPath()
            try {
                System.load(path)
            } catch (_: UnsatisfiedLinkError) {
                // Robolectric gives each test configuration (e.g. NATIVE
                // graphics for Compose) its own classloader, and the JDK
                // refuses to map the SAME file into two of them. Load a
                // private copy instead — each copy binds the native functions
                // for its own classloader; on-disk shards are still protected
                // by qdrant-edge's own file locking.
                val copy = File.createTempFile("edgememo-native-copy-", "." + LIB_RELATIVE_PATH.substringAfterLast('.'))
                File(path).copyTo(copy, overwrite = true)
                copy.deleteOnExit()
                System.load(copy.absolutePath)
            }
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