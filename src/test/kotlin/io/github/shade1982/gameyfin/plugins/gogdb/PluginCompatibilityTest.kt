package io.github.shade1982.gameyfin.plugins.gogdb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import org.gameyfin.pluginapi.core.wrapper.GameyfinPlugin
import org.gameyfin.pluginapi.core.wrapper.PluginContext
import org.gameyfin.pluginapi.gamemetadata.GameMetadataProvider
import org.junit.jupiter.api.Test
import org.pf4j.Extension
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginCompatibilityTest {
    @Test
    fun `packaged plugin jar loads using the Gameyfin 2_5_1 plugin context`() {
        val pluginJar = Path.of(requireNotNull(System.getProperty("pluginJar")))
        val dataDirectory = Path.of("build", "test-plugin-data")
        val context = object : PluginContext {
            override val pluginId = "io.github.shade1982.gameyfin.plugins.gogdb"
            override val pluginVersion = "test"
            override val dataDirectory = dataDirectory
            override val dataService
                get() = error("Data service is not used by this plugin")
        }

        val providerClient = HttpClient(MockEngine { error("Unexpected request in compatibility test") })
        try {
            JarFile(pluginJar.toFile()).use { jar ->
                val manifest = requireNotNull(jar.manifest).mainAttributes
                assertEquals("io.github.shade1982.gameyfin.plugins.gogdb", manifest.getValue("Plugin-Id"))
                assertEquals("0.1.3", manifest.getValue("Plugin-Version"))
                assertEquals(">=2.5.1", manifest.getValue("Plugin-Requires"))

                val extensionIndex = jar.getInputStream(
                    requireNotNull(jar.getJarEntry("META-INF/extensions.idx"))
                ).bufferedReader().use { it.readLines() }
                val providerName =
                    "io.github.shade1982.gameyfin.plugins.gogdb.GogDbPlugin\$GogDbMetadataProvider"
                assertTrue(providerName in extensionIndex)
            }

            ChildFirstPluginClassLoader(pluginJar.toUri().toURL()).use { classLoader ->
                val pluginClass = classLoader.loadClass(
                    "io.github.shade1982.gameyfin.plugins.gogdb.GogDbPlugin"
                )
                val plugin = pluginClass.getConstructor(PluginContext::class.java)
                    .newInstance(context)
                val gameyfinPlugin = assertIs<GameyfinPlugin>(plugin)
                assertEquals(dataDirectory, gameyfinPlugin.dataDirectory)

                val providerClass = classLoader.loadClass(
                    "io.github.shade1982.gameyfin.plugins.gogdb.GogDbPlugin\$GogDbMetadataProvider"
                )
                assertTrue(GameMetadataProvider::class.java.isAssignableFrom(providerClass))
                assertNotNull(providerClass.getAnnotation(Extension::class.java))
                val provider = providerClass.getConstructor(HttpClient::class.java)
                    .newInstance(providerClient)
                assertTrue((provider as GameMetadataProvider).supportedPlatforms.isNotEmpty())
            }
        } finally {
            providerClient.close()
        }
    }

    private class ChildFirstPluginClassLoader(url: URL) :
        URLClassLoader(arrayOf(url), GameyfinPlugin::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("io.github.shade1982.gameyfin.plugins.gogdb.")) {
                return super.loadClass(name, resolve)
            }

            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                return try {
                    findClass(name).also { if (resolve) resolveClass(it) }
                } catch (_: ClassNotFoundException) {
                    super.loadClass(name, resolve)
                }
            }
        }
    }
}
