package dev.earshot.service

import dev.earshot.extras.automation.AutomationRepositoryImpl
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Switching "Persistent notification" off removes the notification the stopped service kept
 * (integration review X19). The automation store does that, and extras may not import the service
 * package (VendorBoundaryTest), so it repeats the notification id: this keeps the two equal.
 */
class KeptNotificationIdTest {

    @Test fun `the automation store cancels the connection notification's own id`() {
        assertEquals(ServiceNotification.NOTIFICATION_ID, AutomationRepositoryImpl.CONNECTION_NOTIFICATION_ID)
    }
}
