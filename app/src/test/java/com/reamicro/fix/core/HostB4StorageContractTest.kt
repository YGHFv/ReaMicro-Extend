package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostB4StorageContractTest {
    private val profile = HostProfile.parse(listOf(
        File("src/main/assets/host-profiles/2.3.3-b4.profile"),
        File("app/src/main/assets/host-profiles/2.3.3-b4.profile"),
    ).first { it.isFile }.readText(Charsets.UTF_8))

    @Test fun storageRoutesKeepDistinctIdentityAndPayloads() {
        for ((route, actual) in mapOf("Storage" to "m69", "ThirdAccount" to "q69", "ThirdLogin" to "t69")) {
            val logical = "app.zhendong.reamicro.Route\$$route"
            assertEquals(actual, profile.classOf(logical))
            assertEquals("a", profile.fieldOf(logical, "type"))
        }
        assertEquals("i49", profile.classOf("app.zhendong.reamicro.Route\$BookPublish"))
        assertEquals("a", profile.fieldOf("app.zhendong.reamicro.Route\$BookPublish", "bookId"))
    }

    @Test fun registeredRouteBridgeDoesNotDependOnOldSetupFacade() {
        for ((role, actual) in mapOf("RouteRegistration" to "ynd", "RouteContent" to "kv",
            "ComposableLambda" to "io1", "RouteDecoder" to "fid", "RouteSetup" to "g0")) {
            assertEquals(actual, profile.classOf("ReaMicroHost.$role"))
        }
    }

    @Test fun storageAndBarcodeUseVerifiedHostUiTypes() {
        assertEquals("sf3", profile.classOf("kotlinx.coroutines.flow.Flow"))
        assertEquals("mq4", profile.classOf("androidx.compose.ui.viewinterop.AndroidView_androidKt"))
        assertEquals("mh0", profile.classOf("ReaMicroHost.BookDetailsTopBar"))
        assertEquals("nj0", profile.classOf("ReaMicroHost.BookOverviewClick"))
    }

    @Test fun associationConstructorOrderKeepsAllTwentySevenFields() {
        val actual = B2BookAssociation.properties.map {
            profile.fieldOf("app.zhendong.reamicro.data.db.entity.Book", it)
        }
        assertEquals(('a'..'z').map(Char::toString) + "A", actual)
        assertEquals("v", profile.fieldOf("app.zhendong.reamicro.data.db.entity.Book", "cloudId"))
        assertEquals("z", profile.fieldOf("app.zhendong.reamicro.data.db.entity.Book", "publisher"))
    }

    @Test fun homeSearchStateKeepsTheTwoMapsAndAllSeventeenFieldsDistinct() {
        assertEquals("ed4", profile.classOf(HostHomeSearchState.CLASS))
        assertEquals(('a'..'q').map(Char::toString),
            HostHomeSearchState.b4Properties.map { profile.fieldOf(HostHomeSearchState.CLASS, it) })
        assertEquals("i", profile.fieldOf(HostHomeSearchState.CLASS, "cloudSearchResults"))
        assertEquals("l", profile.fieldOf(HostHomeSearchState.CLASS, "migrationStatus"))
        assertEquals("n", profile.methodOf("androidx.compose.foundation.lazy.LazyListScope", "item"))
    }

    @Test fun storageProjectionAndHttpUseTheVerifiedB4Roles() {
        assertEquals("rf1", profile.classOf("ReaMicroHost.StorageCollector"))
        assertEquals("uwd", profile.classOf("ReaMicroHost.PagingCache"))
        assertEquals("fsd", profile.classOf("ReaMicroHost.CollectLatest"))
        assertEquals("dr1", profile.classOf("ReaMicroHost.HttpHeadersBuilder"))
    }

    @Test fun importedBookFlowAndWorkerUseVerifiedB4Members() {
        assertEquals("p", profile.methodOf(
            "app.zhendong.reamicro.repository.BookshelfRepository", "liveBookByUrl"))
        assertEquals("w8c", profile.classOf("ReaMicroHost.NativeImportWorker"))
    }

    @Test fun searchUsesEventsInsteadOfGroupLookup() {
        assertEquals("n", profile.methodOf("app.zhendong.reamicro.ui.home.HomeViewModel", "liveBookByGroup"))
        assertNull(profile.methodOf("app.zhendong.reamicro.ui.home.HomeViewModel", "search"))
        for ((role, actual) in mapOf("HomeEvent" to "zc4", "HomeSearchEvent" to "mc4",
            "HomeSearchClose" to "dc4", "HomeCloudTap" to "uc4")) {
            assertEquals(actual, profile.classOf("ReaMicroHost.$role"))
        }
    }
}
