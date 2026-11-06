package com.framemark.native.shell.phone

/**
 * The phone's three top-level pages.
 *
 * Before this existed the shell chose `if (tray.isEmpty) 首页 else 编辑页`, so a
 * project with any photo could never leave the editor: the top bar's 返回 had no
 * outermost target and the system Back press fell through to nothing
 * (`MID-APK-REPORT.md` §6 item 1). The state is now explicit, and [PhonePagePolicy]
 * is the only place that decides a transition — pure, so the transitions are
 * covered by tests instead of a device.
 */
enum class PhonePage {
    Home,
    Editor,
    Settings,
}

object PhonePagePolicy {
    /** 首页 is the entry screen, whatever the project already holds (report §7.1). */
    fun initial(): PhonePage = PhonePage.Home

    /** A picker result (single, multi, sample) always lands in the editor. */
    fun afterPhotosAdded(): PhonePage = PhonePage.Editor

    /** A 最近编辑 row opens the editor only when that photo still exists. */
    fun afterOpenRecent(photoExists: Boolean): PhonePage =
        if (photoExists) PhonePage.Editor else PhonePage.Home

    /**
     * What one Back press does once the editor's own layers (keyboard, full
     * parameter page, focus view, expanded sheet, open sheets) have declined it.
     *
     * `null` means there is nothing left to close, so the platform leaves the app —
     * that is only true on 首页. Both 编辑页 and 设置页 go back to 首页.
     */
    fun back(page: PhonePage): PhonePage? = when (page) {
        PhonePage.Home -> null
        PhonePage.Editor -> PhonePage.Home
        PhonePage.Settings -> PhonePage.Home
    }

    /** True when this page must intercept Back instead of letting the app exit. */
    fun handlesBack(page: PhonePage): Boolean = back(page) != null
}
