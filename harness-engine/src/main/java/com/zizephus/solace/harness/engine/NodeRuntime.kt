package com.zizephus.solace.harness.engine

// Package must match the JNI symbol exported by tools/harness-engine/solaceengine.cc (built as libsolaceengine.so).

/** JNI entry into libsolaceengine.so, which calls node::Start on the given fds. `args` excludes argv[0]. */
object NodeRuntime {
    init {
        System.loadLibrary("c++_shared")
        System.loadLibrary("node")
        System.loadLibrary("solaceengine")
    }

    @JvmStatic
    external fun nativeStart(args: Array<String>, stdinFd: Int, stdoutFd: Int, stderrFd: Int): Int
}
