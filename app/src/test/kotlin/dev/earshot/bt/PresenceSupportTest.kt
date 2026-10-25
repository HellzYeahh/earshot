package dev.earshot.bt

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which Companion Device Manager presence API each Android version gets. */
class PresenceSupportTest {

    @Test fun `presence starts at Android 12 and moves to association ids at 16`() {
        assertEquals(PresenceSupport(canObserve = false, byAssociationId = false), PresenceSupport.forSdk(30, hasCompanionFeature = true))
        assertEquals(PresenceSupport(canObserve = true, byAssociationId = false), PresenceSupport.forSdk(31, hasCompanionFeature = true))
        assertEquals(PresenceSupport(canObserve = true, byAssociationId = false), PresenceSupport.forSdk(35, hasCompanionFeature = true))
        assertEquals(PresenceSupport(canObserve = true, byAssociationId = true), PresenceSupport.forSdk(36, hasCompanionFeature = true))
        assertEquals(PresenceSupport(canObserve = true, byAssociationId = true), PresenceSupport.forSdk(37, hasCompanionFeature = true))
    }

    @Test fun `a phone without companion devices observes nothing`() {
        assertEquals(PresenceSupport(canObserve = false, byAssociationId = false), PresenceSupport.forSdk(37, hasCompanionFeature = false))
    }
}
