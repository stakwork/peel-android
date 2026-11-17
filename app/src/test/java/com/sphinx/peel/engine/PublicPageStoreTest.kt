package com.sphinx.peel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Where a public site's visitor stays signed in (iOS `aPublicSiteKeepsOneStoreOfItsOwn`; docs/phases/42-page-host-logs.md): one
/// kept profile per origin, found again on the next open of any page there, never another site's.
class PublicPageStoreTest {
    private fun kept(address: String) = PublicPage(PublicAppLink.of(address)!!).keptProfile

    @Test fun `a public site keeps one profile of its own, named by iOS's store identifier`() {
        // RFC 9562 version 5 over the origin, in the URL namespace: Python's uuid.uuid5(uuid.NAMESPACE_URL, origin) gives the same.
        val id = PublicPage.storeIdentifier("https://strut.stadeum1.sphinx.chat")
        assertEquals("a49ab031-29db-5436-b8e7-0779241b28fe", id.toString())
        assertEquals(5, id.version())
        assertEquals(2, id.variant())

        val worker = kept("https://strut.stadeum1.sphinx.chat/?app=strut/worker&solo=1&public=1")
        assertEquals("peel_public_a49ab03129db5436b8e70779241b28fe", worker)
        assertEquals(worker, kept("https://strut.stadeum1.sphinx.chat/?app=strut/worker&solo=1&public=1"))
        assertEquals(worker, kept("https://STRUT.stadeum1.sphinx.chat:443/?app=other&public=1"))
        assertNotEquals(worker, kept("https://strut.stadeum2.sphinx.chat/?app=strut/worker&solo=1&public=1"))
        assertNotEquals(worker, kept("https://strut.stadeum1.sphinx.chat:8443/?app=strut/worker&solo=1&public=1"))
    }

    @Test fun `an org's page keeps nothing`() {
        assertNull(OrgPageRequest("https://acme.sphinx.chat/lab/?app=x&solo=1", "tok", "acme", "Acme", "Board").keptProfile)
    }
}
