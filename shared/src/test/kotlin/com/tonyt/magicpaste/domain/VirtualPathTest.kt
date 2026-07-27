package com.tonyt.magicpaste.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VirtualPathTest {

    @Test
    fun `normalizes to a canonical form`() {
        assertEquals("/", VirtualPath.normalize(""))
        assertEquals("/", VirtualPath.normalize("/"))
        assertEquals("/", VirtualPath.normalize("///"))
        assertEquals("/DCIM", VirtualPath.normalize("DCIM"))
        assertEquals("/DCIM", VirtualPath.normalize("/DCIM/"))
        assertEquals("/DCIM/Camera", VirtualPath.normalize("/DCIM//Camera"))
        assertEquals("/DCIM/Camera", VirtualPath.normalize("/./DCIM/./Camera"))
    }

    @Test
    fun `keeps names that merely look suspicious`() {
        assertEquals("/..hidden", VirtualPath.normalize("/..hidden"))
        assertEquals("/a..b", VirtualPath.normalize("/a..b"))
        assertEquals("/...", VirtualPath.normalize("/..."))
    }

    @Test
    fun `refuses every spelling of climbing out`() {
        val attempts = listOf(
            "..",
            "../",
            "/..",
            "/../etc/passwd",
            "/DCIM/../../etc/passwd",
            "/DCIM/..",
            "..\\windows",
            "/DCIM\\..\\..",
        )
        for (attempt in attempts) {
            val failure = assertFailsWith<FileStoreException>("'$attempt' should have been refused") {
                VirtualPath.normalize(attempt)
            }
            assertEquals(FileStoreException.Kind.Invalid, failure.kind)
        }
    }

    @Test
    fun `refuses control characters and NUL`() {
        assertFailsWith<FileStoreException> { VirtualPath.normalize("/DCIM/a\u0000b") }
        assertFailsWith<FileStoreException> { VirtualPath.normalize("/DCIM/a\nb") }
    }

    @Test
    fun `spaces and unicode are ordinary characters in a name`() {
        assertEquals("/DCIM/my holiday.jpg", VirtualPath.normalize("/DCIM/my holiday.jpg"))
        assertEquals("/Δοκιμή", VirtualPath.normalize("/Δοκιμή"))
        assertEquals("my holiday.jpg", VirtualPath.requireSimpleName("my holiday.jpg"))
    }

    @Test
    fun `a simple name may not carry a separator`() {
        assertEquals("holiday.jpg", VirtualPath.requireSimpleName("holiday.jpg"))
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "/etc", "a\u0000b")) {
            assertFailsWith<FileStoreException>("'$bad' should have been refused") {
                VirtualPath.requireSimpleName(bad)
            }
        }
    }

    @Test
    fun `parent walks up and stops at the root`() {
        assertEquals("/DCIM", VirtualPath.parentOf("/DCIM/Camera"))
        assertEquals("/", VirtualPath.parentOf("/DCIM"))
        assertNull(VirtualPath.parentOf("/"))
    }

    @Test
    fun `join does not double the separator at the root`() {
        assertEquals("/a", VirtualPath.join("/", "a"))
        assertEquals("/a/b", VirtualPath.join("/a", "b"))
    }
}
