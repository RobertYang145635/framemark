package com.framemark.native.arch

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ratchet hygiene (ARCH-MODULES.md §2, "只许减不许增").
 *
 * - every allowlist entry must belong to a rule that is actually enforced, and
 * - every allowlist entry must still match a real violation.
 *
 * Counter-example this test blocks: someone fixes a coupling but leaves the allowlist
 * line behind, so the next regression is silently accepted. A stale entry fails here
 * with `stale allowlist entry`.
 */
class ArchAllowlistHygieneKonsistTest {
    @Test
    fun allowlistHasNoUnknownRulesAndNoStaleEntries () {
        val entries = ArchAllowlist.entries
        val unknownRules = entries.filter { it.rule !in Arch.rules }
        assertTrue(
            unknownRules.isEmpty(),
            "unknown rules in violations-allowlist.txt: $unknownRules (known: ${Arch.rules.sorted()})",
        )
        val violations = Arch.allViolations()
        val actual = violations.map { it.key }.toSet()
        val stale = entries.filter { it.key !in actual }
        assertTrue(
            stale.isEmpty(),
            buildString {
                appendLine("stale allowlist entries (the violation is gone — delete the line):")
                stale.take(40).forEach { appendLine("  - $it") }
                if (stale.size > 40) appendLine("  ... ${stale.size - 40} more")
            },
        )
        val counts = violations.groupingBy { it.rule }.eachCount()
        println("ARCH1-ALLOWLIST entries=${entries.size} rules=${counts.toSortedMap()}")
        println("ARCH1-ALLOWLIST per-rule entries=" + entries.groupingBy { it.rule }.eachCount().toSortedMap())
    }
}
