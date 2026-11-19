package com.framemark.native.workflow

/**
 * Desktop: same runtime call as Android. The desktop batch is CPU work too
 * (the E5/IR worker is a child process per render), so one core per worker is the
 * whole intent; 0 when the platform refuses to answer, which falls back to the
 * two-worker floor.
 */
actual fun platformProcessorCount(): Int =
    runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(0)
