package com.sphinx.peel.storage

import androidx.test.core.app.ApplicationProvider
import com.sphinx.peel.engine.SiteLocationGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/// `site_location`: put, replace, list, remove, clear.
@RunWith(RobolectricTestRunner::class)
class StorageSiteLocationTest {
    private fun newStorage() = Storage(ApplicationProvider.getApplicationContext(), account = "test-${System.nanoTime()}")

    @Test fun `an answer is saved, replaced, removed and all cleared`() {
        val storage = newStorage()
        val a = "https://a.example.com"
        val b = "http://localhost:3000"
        assertNull(storage.siteLocation(a))
        val v = storage.siteLocationsVersion.value
        storage.putSiteLocation(SiteLocationGrant(a, true, false, 5))
        storage.putSiteLocation(SiteLocationGrant(b, false, false, 6))
        assertEquals(SiteLocationGrant(a, true, false, 5), storage.siteLocation(a))
        storage.putSiteLocation(SiteLocationGrant(a, true, true, 7))
        assertEquals(true, storage.siteLocation(a)!!.precise)
        assertEquals(true, storage.anySitePrecise())
        assertEquals(listOf(b, a).sorted(), storage.siteLocations().map { it.origin })
        assertEquals(true, storage.siteLocationsVersion.value > v)
        storage.removeSiteLocation(a)
        assertNull(storage.siteLocation(a))
        assertEquals(false, storage.anySitePrecise())
        storage.clearSiteLocations()
        assertEquals(emptyList<SiteLocationGrant>(), storage.siteLocations())
    }
}
