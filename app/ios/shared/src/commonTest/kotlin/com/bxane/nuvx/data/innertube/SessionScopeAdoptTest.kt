package com.bxane.nuvx.data.innertube

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull













class SessionScopeAdoptTest {

    private fun reset() {
        Innertube.cookie = null
        Innertube.adoptPageScope(null, null, null)
    }



    @Test
    fun a_plain_id_is_returned_as_is() {
        try {
            assertEquals("ds-123", Innertube.normalizeDataSyncId("ds-123"))
        } finally {
            reset()
        }
    }

    @Test
    fun a_delegated_id_resolves_to_the_active_half() {
        try {
            assertEquals("delegated", Innertube.normalizeDataSyncId("account||delegated"))
        } finally {
            reset()
        }
    }

    @Test
    fun an_empty_active_half_falls_back_to_the_account_half() {
        try {
            assertEquals("account", Innertube.normalizeDataSyncId("account||"))
        } finally {
            reset()
        }
    }

    @Test
    fun blank_is_absent() {
        try {
            assertNull(Innertube.normalizeDataSyncId(null))
            assertNull(Innertube.normalizeDataSyncId(""))
            assertNull(Innertube.normalizeDataSyncId("   "))
            assertNull(Innertube.normalizeDataSyncId("||"))
        } finally {
            reset()
        }
    }



    @Test
    fun an_adopted_signed_in_page_is_the_current_identity() {
        try {
            Innertube.cookie = "SAPISID=secret"
            Innertube.adoptSessionScope(
                pageId = "page-1",
                dataSyncId = "ds-1",
                authUser = "1",
                visitorData = null,
                clientVersion = null,
                loggedIn = true,
            )
            val identity = Innertube.currentIdentity()
            assertEquals("page-1", identity?.pageId)
            assertEquals("ds-1", identity?.dataSyncId)
            assertEquals("1", identity?.authUser)
        } finally {
            reset()
        }
    }

    @Test
    fun a_signed_out_page_scopes_to_nothing() {


        try {
            Innertube.cookie = "SAPISID=secret"
            Innertube.adoptSessionScope(
                pageId = null,
                dataSyncId = "ds-1",
                authUser = "0",
                visitorData = null,
                clientVersion = null,
                loggedIn = false,
            )
            assertNull(Innertube.currentIdentity())
        } finally {
            reset()
        }
    }

    @Test
    fun blank_fields_are_absent_not_identities() {
        try {
            Innertube.cookie = "SAPISID=secret"
            Innertube.adoptSessionScope(
                pageId = "  ",
                dataSyncId = "",
                authUser = "",
                visitorData = "  ",
                clientVersion = "",
                loggedIn = true,
            )

            assertNull(Innertube.currentIdentity())
        } finally {
            reset()
        }
    }
}
