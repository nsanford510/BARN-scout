package com.vpt.scout.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutVerdictTest {

    @Test
    fun `yes and maybe are follow-ups and no is not`() {
        assertTrue(verdictFollowUp(ScoutVerdict.YES))
        assertTrue(verdictFollowUp(ScoutVerdict.MAYBE))
        assertFalse(verdictFollowUp(ScoutVerdict.NO))
    }

    @Test
    fun `verdictNotes keeps trimmed notes for maybe`() {
        assertEquals("gate locked", verdictNotes(ScoutVerdict.MAYBE, "  gate locked \n"))
    }

    @Test
    fun `verdictNotes is null for blank maybe notes`() {
        assertNull(verdictNotes(ScoutVerdict.MAYBE, "   "))
    }

    @Test
    fun `verdictNotes drops notes for yes and no`() {
        assertNull(verdictNotes(ScoutVerdict.YES, "leftover"))
        assertNull(verdictNotes(ScoutVerdict.NO, "leftover"))
    }
}
