package com.framemark.native.shell.phone

import androidx.compose.ui.unit.dp

/**
 * AU4-05 / 10-spec: the smallest touch target a phone control should have.
 *
 * The spec asks for 48 dp; 44 dp is the WCAG 2.5.5 "AAA" floor and the value AU4
 * used when it measured six controls below the line (`按住看原图` 34, `调整样式` 30,
 * `预设` 30, `展开参数` 33, `切换照片` 40, `常用 chip` 36 — uiautomator bounds ÷ 3.5
 * at density 560).
 *
 * Two of the six were fixed by growing the *container* because their parent is tall
 * (the 52 dp editor top bar and the stage overlay): `切换照片` 40 → 44, `按住看原图`
 * 34 → 44 (the scrim pill stays 34, the transparent box around it is 44).
 *
 * The other four live inside the compact sheet's 28–36 dp horizontal bands, and there
 * the platform forbids the obvious fixes:
 *  - `Modifier.heightIn(min = 44.dp)` / `minimumInteractiveComponentSize()` grow the
 *    *layout*, which the six-tier sheet budget
 *    ([com.framemark.native.shell.PhoneCompactSheetMetrics], pinned by
 *    `PhoneEditorCompactSheetTest`) does not have room for: the 128 dp card and its
 *    name would fall below the fold on 390×844 — the defect WP-P1-2 closed.
 *  - a taller *hit* box (`Modifier.layout` reporting 44 dp, measured 2026-11-07) does
 *    not help: Compose gates the pointer at the band's own bounds, so the extra dp are
 *    dead. Measured on `8267fe3e`: a tap 6 dp above `展开参数` reached the sheet grabber
 *    (level 0 → 1, i.e. the *wrong* control), a tap 6 dp above `预设` did nothing, and
 *    a tap 5 dp above `热门` did nothing.
 *
 * So the remaining three stay at 30/33/36 dp until either (a) the sheet bands are
 * allowed to grow (a product decision on the P1-2 budget), or (b) a touch delegate is
 * added on a taller ancestor (a Compose `pointerInput` on the sheet column that
 * dispatches taps landing in a slop ring — pays for itself only if it also has to
 * keep scroll/drag gestures intact). 预设 is no longer one of them: its primary
 * entry is now the tool bar's full-height action cell, where the band no longer
 * gates the pointer. See `reports\WP-X3-REPORT.md` §4.4.
 */
val PhoneTouchTarget = 44.dp
