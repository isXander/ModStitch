package dev.isxander.modstitch.base.moddevgradle

import com.electronwill.nightconfig.core.Config
import com.electronwill.nightconfig.toml.TomlFormat
import dev.isxander.modstitch.base.BaseCommonImpl
import dev.isxander.modstitch.base.FutureNamedDomainObjectProvider
import dev.isxander.modstitch.base.extensions.MixinSettingsSerializer
import dev.isxander.modstitch.base.extensions.modstitch
import dev.isxander.modstitch.util.PlatformExtensionInfo
import dev.isxander.modstitch.util.Platform
import dev.isxander.modstitch.util.Side
import dev.isxander.modstitch.util.addCamelCasePrefix
import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import net.neoforged.moddevgradle.legacyforge.dsl.LegacyForgeExtension
import net.neoforged.moddevgradle.legacyforge.dsl.MixinExtension
import net.neoforged.moddevgradle.legacyforge.dsl.ObfuscationExtension
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.*
import org.gradle.kotlin.dsl.assign
import org.gradle.language.jvm.tasks.ProcessResources
import org.slf4j.event.Level

class BaseModdevgradleImpl(private val type: MDGType) : BaseCommonImpl<BaseModDevGradleExtension>(type.platform) {
    private lateinit var remapConfiguration: Configuration

    override val platformExtensionInfo = PlatformExtensionInfo(
        "msModdevgradle",
        BaseModDevGradleExtension::class,
        BaseModDevGradleExtensionImpl::class,
        BaseModDevGradleExtensionDummy::class,
    )

    override fun apply(target: Project) {
        val enabler = when (type) {
            MDGType.Regular -> {
                target.pluginManager.apply("net.neoforged.moddev")
                RegularEnableConfiguration(this, target.extensions.getByType<NeoForgeExtension>())
            }
            MDGType.Legacy -> {
                target.pluginManager.apply("net.neoforged.moddev.legacyforge")
                LegacyEnableConfiguration(this, target.extensions.getByType<LegacyForgeExtension>())
            }
        }

        val neoExt = createRealPlatformExtension(target, enabler, type)!!

        super.apply(target)

        println("Applying ModDevGradle for ${target.name}")

        neoExt.configureNeoforge {
            // version and neoForm version are set through functions
            // called via the extension

            parchment {
                parchmentArtifact = target.modstitch.parchment.parchmentArtifact
                enabled = target.modstitch.parchment.enabled
            }

            validateAccessTransformers = true

            runs {
                configureEach {
                    // Recommended practice per Neoforge MDK
                    logLevel = Level.DEBUG
                }
            }
        }

        target.configurations.create("localRuntime") localRuntime@{
            target.configurations.named("runtimeOnly") {
                extendsFrom(this@localRuntime)
            }
        }

        target.modstitch.modLoaderManifest = when (type) {
            MDGType.Regular -> Platform.MDG.modManifest
            MDGType.Legacy -> Platform.MDGLegacy.modManifest
        }
        target.modstitch.mixin.serializer.convention(getMixinSerializer())
    }

    fun enable(target: Project) {
        if (target.pluginManager.hasPlugin(EnabledMarkerPlugin.ID)) {
            return
        }

        val neoExt = target.platformExt

        if (type == MDGType.Legacy) {
            // proxy configurations will add remap configurations to this
            remapConfiguration = target.configurations.create("modstitchMdgRemap")
            target.obfuscation.createRemappingConfiguration(remapConfiguration)
        }

        if (type == MDGType.Legacy) {
            setupLegacyMixin(target)
        }
    }

    override fun applyMetadataStringReplacements(target: Project): TaskProvider<ProcessResources> {
        val generateModMetadata = super.applyMetadataStringReplacements(target)

        // Generate mod metadata every project reload, instead of manually
        // (see `generateModMetadata` task in `common.gradle.kts`)
        target.msModdevgradle.configureNeoforge {
            ideSyncTask(generateModMetadata)
        }

        return generateModMetadata
    }

    override fun createProxyConfigurations(target: Project, configuration: FutureNamedDomainObjectProvider<Configuration>, defer: Boolean) {
        val proxyModConfigurationName = configuration.name.addCamelCasePrefix("modstitchMod")
        val proxyRegularConfigurationName = configuration.name.addCamelCasePrefix("modstitch")

        // already created
        if (target.configurations.find { it.name == proxyModConfigurationName } != null) {
            return
        }

        fun deferred(action: (Configuration) -> Unit) {
            if (!defer) return action(configuration.get())
            return target.onMdgEnable { action(configuration.get()) }
        }

        target.configurations.create(proxyModConfigurationName) proxy@{
            deferred {
                it.extendsFrom(this@proxy)
            }

            target.onMdgEnable {
                if (type == MDGType.Legacy)
                    remapConfiguration.extendsFrom(this@proxy)
            }
        }

        target.configurations.create(proxyRegularConfigurationName) proxy@{
            deferred {
                it.extendsFrom(this@proxy)
            }

            target.onMdgEnable {
                target.configurations.named("additionalRuntimeClasspath") {
                    extendsFrom(this@proxy)
                }
            }
        }
    }

    override fun configureJiJConfiguration(target: Project, configuration: Configuration) {
        target.onMdgEnable {
            target.configurations.named("jarJar") {
                extendsFrom(configuration)
            }
        }
    }

    override fun createProxyConfigurations(target: Project, sourceSet: SourceSet) {
        super.createProxyConfigurations(target, sourceSet)

        if (sourceSet.name == SourceSet.MAIN_SOURCE_SET_NAME) {
            target.onMdgEnable {
                createProxyConfigurations(target, FutureNamedDomainObjectProvider.from(target.configurations, "localRuntime"), defer = true)
            }
        }
    }

    private fun setupLegacyMixin(target: Project) {
        val mixin = target.modstitch.mixin

        target.dependencies {
            "annotationProcessor"("org.spongepowered:mixin:0.8.5:processor")
        }

        val mainSourceSet = target.extensions.getByType<SourceSetContainer>()["main"]
        mixin.configs.whenObjectAdded config@{
            target.mixin.apply {
                add(mainSourceSet, this@config.refmap.get())
                config(this@config.config.get())
            }
        }

        target.tasks.named<Jar>("jar") {
            doFirst {
                manifest.attributes["MixinConfigs"] = mixin.configs.joinToString(",") { it.config.get() }
            }
        }
    }

    private fun getMixinSerializer(): MixinSettingsSerializer = { configs, logger ->
        val toml = Config.inMemory()
        toml.add("mixins", configs.map {
            if (it.side.getOrElse(Side.Both) != Side.Both) {
                logger.warn("Side-specific mixins are not supported in MDG. Ignoring side for ${it.name}")
            }

            Config.inMemory().apply { set("config", it.config.get()) }
        })

        TomlFormat.instance().createWriter().writeToString(toml).trim()
    }

    private val Project.platformExt: BaseModDevGradleExtension
        get() = extensions.getByType<BaseModDevGradleExtension>()

    private val Project.obfuscation: ObfuscationExtension
        get() = if (type == MDGType.Legacy) extensions.getByType<ObfuscationExtension>() else error("Obfuscation is not available in this context")

    private val Project.mixin: MixinExtension
        get() = if (type == MDGType.Legacy) extensions.getByType<MixinExtension>() else error("Mixin is not available in this context")

    private fun Project.onMdgEnable(action: () -> Unit) = onEnable(this) { action() }

    override fun onEnable(target: Project, action: Action<Project>) {
        target.pluginManager.withPlugin(EnabledMarkerPlugin.ID) {
            action.execute(target)
        }
    }
}