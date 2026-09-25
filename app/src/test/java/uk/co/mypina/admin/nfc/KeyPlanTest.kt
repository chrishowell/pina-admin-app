package uk.co.mypina.admin.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KeyPlanTest {
    @Test fun keys1And2_factoryChanged_pinaSkipped_otherRefused() {
        for (slot in 1..2) {
            assertEquals(KeyAction.CHANGE, planKeyChange(slot, currentVersion = 0, targetVersion = 1, authKeyIsTarget = false))
            assertEquals(KeyAction.SKIP, planKeyChange(slot, currentVersion = 1, targetVersion = 1, authKeyIsTarget = false))
            val e = assertThrows(TagWriteException::class.java) { planKeyChange(slot, 5, 1, false) }
            assertEquals(if (slot == 1) Step.KEY1 else Step.KEY2, e.step)
        }
    }

    @Test fun key0_skippedOnlyWhenAuthenticatedWithPinasKeyAtTargetVersion() {
        assertEquals(KeyAction.SKIP, planKeyChange(0, 1, 1, authKeyIsTarget = true))
        assertEquals(KeyAction.CHANGE, planKeyChange(0, 0, 1, authKeyIsTarget = false))  // factory
        assertEquals(KeyAction.CHANGE, planKeyChange(0, 1, 1, authKeyIsTarget = false))  // zeros but version 1
        assertEquals(KeyAction.CHANGE, planKeyChange(0, 0, 1, authKeyIsTarget = true))   // right key, wrong version
    }

    @Test fun orderKey0Candidates_followsKey0Version() {
        val zero = ByteArray(16)
        val derived = "5004BF991F408672B1EF00F08F9E8647".hexToBytes()
        val other = "F3847D627727ED3BC9C4CC050489B966".hexToBytes()
        fun order(v: Int?, vararg c: ByteArray) = orderKey0Candidates(c.toList(), v, 1).map { it.toHex() }
        assertEquals(listOf(derived, other, zero).map { it.toHex() }, order(1, zero, derived, other))
        assertEquals(listOf(zero, derived, other).map { it.toHex() }, order(0, derived, zero, other))
        assertEquals(listOf(derived, zero).map { it.toHex() }, order(null, derived, zero))
        assertEquals(listOf(zero, derived).map { it.toHex() }, order(7, zero, derived))
    }
}
