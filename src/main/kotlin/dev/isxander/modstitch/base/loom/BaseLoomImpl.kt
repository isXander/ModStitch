package dev.isxander.modstitch.base.loom

import com.google.gson.Gson
import dev.isxander.modstitch.base.BaseCommonImpl
import dev.isxander.modstitch.base.extensions.MixinSettingsSerializer
import dev.isxander.modstitch.base.extensions.modstitch
import dev.isxander.modstitch.util.Platform
import dev.isxander.modstitch.util.PlatformExtensionInfo
import dev.isxander.modstitch.util.Side
import dev.isxander.modstitch.util.addCamelCasePrefix
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.util.Constants
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import java.util.function.Function

class BaseLoomImpl : BaseCommonImpl<BaseLoomExtension>(Platform.Loom) {
    override val platformExtensionInfo = PlatformExtensionInfo(
        "msLoom",
        BaseLoomExtension::class,
        BaseLoomExtensionImpl::class,
        BaseLoomExtensionDummy::class
    )

    override fun apply(target: Project) {
        target.pluginManager.apply("fabric-loom")
        super.apply(target)

        val fabricExt = createRealPlatformExtension(target)!!

        target.dependencies {
            "minecraft"(target.modstitch.minecraftVersion.map { "com.mojang:minecraft:$it" })
            "mappings"(fabricExt.loomExtension.officialMojangMappings())

            "modImplementation"(fabricExt.fabricLoaderVersion.map { "net.fabricmc:fabric-loader:$it" })
        }

        target.afterEvaluate {
            if (target.modstitch.parchment.enabled.get()) {
                error("Parchment is not supported on Loom yet.")
            }
        }

        target.modstitch.modLoaderManifest = Platform.Loom.modManifest
        target.modstitch.mixin.serializer.convention(getMixinSerializer())
    }

    override fun applyDefaultRepositories(repositories: RepositoryHandler) {
        super.applyDefaultRepositories(repositories)
        repositories.maven("https://maven.fabricmc.net") { name = "FabricMC" }
    }

    override fun applyMetadataStringReplacements(target: Project): TaskProvider<ProcessResources> {
        val generateModMetadata = super.applyMetadataStringReplacements(target)

        target.tasks.named("ideaSyncTask") {
            dependsOn("generateModMetadata")
        }

        return generateModMetadata
    }

    override fun createProxyConfigurations(target: Project, sourceSet: SourceSet) {
        if (sourceSet.name != SourceSet.MAIN_SOURCE_SET_NAME) {
            target.loom.createRemapConfigurations(sourceSet)
        }

        super.createProxyConfigurations(target, sourceSet)
    }

    override fun createProxyConfigurations(target: Project, configuration: Configuration) {
        val remapConfiguration = target.loom.remapConfigurations
            .find { it.targetConfigurationName.get() == configuration.name }
            ?: error("Loom has not created a remap configuration for ${configuration.name}, modstitch cannot proxy it.")

        val proxyModConfigurationName = configuration.name.addCamelCasePrefix("modstitchMod")
        val proxyRegularConfigurationName = configuration.name.addCamelCasePrefix("modstitch")

        target.configurations.create(proxyModConfigurationName) proxy@{
            target.configurations.named(remapConfiguration.name) {
                extendsFrom(this@proxy)
            }
        }
        target.configurations.create(proxyRegularConfigurationName) proxy@{
            configuration.extendsFrom(this@proxy)
        }
    }

    override fun configureJiJConfiguration(target: Project, configuration: Configuration) {
        target.configurations.named(Constants.Configurations.INCLUDE) {
            extendsFrom(configuration)
        }
    }

    private fun getMixinSerializer(): MixinSettingsSerializer = Function { configs ->
        configs.map {
            FMJMixinConfig(it.config.get(), when (it.side.get()) {
                Side.Both -> "*"
                Side.Client -> "client"
                Side.Server -> "server"
            })
        }.let { Gson().toJson(it) }
    }

    private val Project.loom: LoomGradleExtensionAPI
        get() = extensions.getByType<LoomGradleExtensionAPI>()
}