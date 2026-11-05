package com.framemark.native.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserLogoTest {
    @Test
    fun design_value_is_namespaced_and_round_trips() {
        assertTrue(UserLogoLibrary.isUserLogo("user:ul-1"))
        assertEquals("ul-1", UserLogoLibrary.idOf("user:ul-1"))
        assertFalse(UserLogoLibrary.isUserLogo("ver:leica:cut"))
        assertNull(UserLogoLibrary.idOf("ver:leica:cut"))
    }

    @Test
    fun mime_accepts_declared_type_or_extension() {
        assertEquals(UserLogoLibrary.MIME_PNG, UserLogoLibrary.normalizeMime("image/png", "a.bin"))
        assertEquals(UserLogoLibrary.MIME_WEBP, UserLogoLibrary.normalizeMime(null, "Logo.WEBP"))
        assertEquals(UserLogoLibrary.MIME_PNG, UserLogoLibrary.normalizeMime("", "logo.png"))
        assertNull(UserLogoLibrary.normalizeMime("image/jpeg", "logo.jpg"))
    }

    @Test
    fun validation_enforces_format_size_and_edges() {
        assertNull(UserLogoLibrary.validate(800, 200, 1024, UserLogoLibrary.MIME_PNG))
        assertEquals("只支持 PNG 或 WebP", UserLogoLibrary.validate(800, 200, 1024, "image/jpeg"))
        assertEquals("图片不能超过 4 MB", UserLogoLibrary.validate(800, 200, UserLogoLibrary.MAX_BYTES + 1, UserLogoLibrary.MIME_PNG))
        assertEquals("图片最长边不能超过 4096", UserLogoLibrary.validate(4097, 200, 1024, UserLogoLibrary.MIME_PNG))
    }

    @Test
    fun name_is_safe_and_keeps_the_format_extension() {
        val name = UserLogoLibrary.safeName("../../我的 Logo!.PNG", UserLogoLibrary.MIME_PNG)
        assertTrue(name.endsWith(".png"))
        assertFalse(name.contains("..") || name.contains("/") || name.contains("\\"))
        assertTrue(name.contains("Logo"))
    }
}
