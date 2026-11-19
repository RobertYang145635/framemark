package com.framemark.native.workflow

/**
 * Android: the JVM's own view of the device. `/proc/cpuinfo` and
 * `availableProcessors()` agree here, and the latter is the one the runtime
 * actually sizes its pools by — a core the process may not use is not a core this
 * batch should render on.
 *
 * 0 when the platform refuses to answer; [batchWorkerCount] then falls back to the
 * two-worker floor.
 */
actual fun platformProcessorCount(): Int =
    runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(0)
