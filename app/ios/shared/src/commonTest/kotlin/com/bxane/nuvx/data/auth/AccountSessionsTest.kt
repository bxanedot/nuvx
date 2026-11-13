package com.bxane.nuvx.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull










class AccountSessionsTest {



    private fun profile(
        id: String = "p1",
        name: String = "Channel",
        pageId: String? = null,
        dataSyncId: String? = null,
        authUser: String? = null,
    ) = YouTubeProfile(profileId = id, name = name, pageId = pageId, dataSyncId = dataSyncId, authUser = authUser)

    private fun account(
        id: String = "a1",
        cookie: String = "SID=x",
        profiles: List<YouTubeProfile> = emptyList(),
        active: String? = null,
    ) = GoogleAccountSession(
        accountId = id, cookie = cookie, profiles = profiles, activeProfileId = active
    )



    @Test
    fun a_session_id_prefers_the_data_sync_id() {



        assertEquals("ds-123", sessionIdOf("SID=whatever", "ds-123"))
    }

    @Test
    fun a_session_id_falls_back_to_the_cookie_digest() {
        val id = sessionIdOf("SID=abc", null)
        assertEquals(24, id.length)
        assertEquals(id, sessionIdOf("SID=abc", null))
    }

    @Test
    fun different_cookies_get_different_session_ids() {
        assertNotEquals(sessionIdOf("SID=abc", null), sessionIdOf("SID=xyz", null))
    }

    @Test
    fun a_blank_data_sync_id_is_treated_as_absent() {


        assertEquals(sessionIdOf("SID=abc", null), sessionIdOf("SID=abc", ""))
        assertEquals(sessionIdOf("SID=abc", null), sessionIdOf("SID=abc", "   "))
    }



    @Test
    fun a_profile_id_prefers_the_page_id() {
        assertEquals("page-1", profileIdOf("page-1", "ds-1", "Name"))
    }

    @Test
    fun a_profile_id_falls_back_to_the_data_sync_id() {
        assertEquals("ds-1", profileIdOf(null, "ds-1", "Name"))
    }

    @Test
    fun a_profile_id_falls_back_last_to_a_name_digest() {
        val id = profileIdOf(null, null, "Some Channel")
        assertEquals("profile:", id.take(8))



        assertEquals(8 + 16, id.length)
    }

    @Test
    fun the_name_digest_is_stable_across_calls() {



        assertEquals(profileIdOf(null, null, "X"), profileIdOf(null, null, "X"))
    }

    @Test
    fun two_channels_with_the_same_name_collide_on_the_name_digest() {


        assertEquals(profileIdOf(null, null, "Acme"), profileIdOf(null, null, "Acme"))
    }

    @Test
    fun a_blank_page_id_falls_through_to_the_next_link() {
        assertEquals("ds-1", profileIdOf("", "ds-1", "Name"))
        assertEquals("ds-1", profileIdOf("  ", "ds-1", "Name"))
    }

    @Test
    fun a_profile_with_a_real_id_ignores_a_colliding_name() {

        assertNotEquals(
            profileIdOf("page-1", null, "Acme"),
            profileIdOf("page-2", null, "Acme"),
        )
    }



    @Test
    fun profiles_flatten_in_account_order_then_profile_order() {
        val accounts = listOf(
            account(id = "a1", profiles = listOf(profile("p1"), profile("p2"))),
            account(id = "a2", profiles = listOf(profile("p3"))),
        )
        assertEquals(
            listOf("a1" to "p1", "a1" to "p2", "a2" to "p3"),
            flattenedProfiles(accounts),
        )
    }

    @Test
    fun an_account_with_no_profiles_contributes_nothing() {
        val accounts = listOf(account(id = "a1"), account(id = "a2", profiles = listOf(profile("p9"))))
        assertEquals(listOf("a2" to "p9"), flattenedProfiles(accounts))
    }

    @Test
    fun the_order_is_stored_order_not_alphabetical() {


        val accounts = listOf(
            account(id = "zzz", profiles = listOf(profile("p1"))),
            account(id = "aaa", profiles = listOf(profile("p2"))),
        )
        assertEquals(listOf("zzz" to "p1", "aaa" to "p2"), flattenedProfiles(accounts))
    }



    @Test
    fun the_next_profile_is_the_one_after_it() {
        val accounts = listOf(
            account(id = "a1", profiles = listOf(profile("p1"), profile("p2"))),
            account(id = "a2", profiles = listOf(profile("p3"))),
        )
        assertEquals("a2" to "p3", adjacentProfile(accounts, "a1", "p2", forward = true))
    }

    @Test
    fun the_previous_profile_is_the_one_before_it() {
        val accounts = listOf(account(id = "a1", profiles = listOf(profile("p1"), profile("p2"))))
        assertEquals("a1" to "p1", adjacentProfile(accounts, "a1", "p2", forward = false))
    }

    @Test
    fun stepping_past_the_end_does_nothing_rather_than_wrapping() {


        val accounts = listOf(account(id = "a1", profiles = listOf(profile("p1"))))
        assertNull(adjacentProfile(accounts, "a1", "p1", forward = true))
        assertNull(adjacentProfile(accounts, "a1", "p1", forward = false))
    }

    @Test
    fun an_unknown_selection_has_no_neighbour() {
        val accounts = listOf(account(id = "a1", profiles = listOf(profile("p1"))))
        assertNull(adjacentProfile(accounts, "a1", "nope", forward = true))
        assertNull(adjacentProfile(accounts, "nope", "p1", forward = true))
        assertNull(adjacentProfile(accounts, null, null, forward = true))
    }

    @Test
    fun adjacency_spans_accounts() {
        val accounts = listOf(
            account(id = "a1", profiles = listOf(profile("p1"))),
            account(id = "a2", profiles = listOf(profile("p2"))),
        )
        assertEquals("a2" to "p2", adjacentProfile(accounts, "a1", "p1", forward = true))
        assertEquals("a1" to "p1", adjacentProfile(accounts, "a2", "p2", forward = false))
    }
}
