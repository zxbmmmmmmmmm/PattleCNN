package org.bettafish.huelab

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertNotNull

class NavigationConfigurationTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun everyRouteIsRegisteredForNavKeyPolymorphism() {
        val module = HueLabNavigationSavedStateConfiguration.serializersModule
        val routes: List<NavKey> = listOf(
            AuthRoute,
            AnnotateRoute,
            HistoryRoute,
            HistoryEditorRoute("image-id", "image.jpg", listOf("#000000", "#555555", "#AAAAAA", "#FFFFFF")),
        )

        routes.forEach { route ->
            assertNotNull(
                module.getPolymorphic(NavKey::class, route),
                "Missing NavKey serializer for ${route::class.simpleName}",
            )
        }
    }
}
