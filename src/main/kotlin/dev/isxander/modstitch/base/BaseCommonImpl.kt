package dev.isxander.modstitch.base

import dev.isxander.modstitch.*
import dev.isxander.modstitch.base.extensions.*
import dev.isxander.modstitch.util.MODSTITCH_VERSION
import dev.isxander.modstitch.util.Platform
import dev.isxander.modstitch.util.platform
import net.fabricmc.loom.util.Constants
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.*
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.plugins.ide.idea.model.IdeaModel

abstract class BaseCommonImpl<T : Any>(
    val platform: Platform,
) : PlatformPlugin<T>() {
    override fun apply(target: Project) {
        target.logger.lifecycle("Modstitch/Base: $MODSTITCH_VERSION")

        // Set the property for use elsewhere
        target.platform = platform

        // Apply the necessary plugins
        target.pluginManager.apply("java-library")
        target.pluginManager.apply("idea")

        // Create our plugin extension
        val msExt = target.extensions.create(
            ModstitchExtension::class.java,
            "modstitch",
            ModstitchExtensionImpl::class.java,
            this,
        )

        // Ensure the archivesBaseName is our mod-id
        target.pluginManager.withPlugin("base") {
            target.extensions.configure<BasePluginExtension> {
                archivesName.set(msExt.metadata.modId)
            }
        }

        // Project version and group does not support lazy configuration.
        // Must do in an afterEvaluate block
        target.afterEvaluate {
            target.group = msExt.metadata.modGroup.get()
            target.version = msExt.metadata.modVersion.get()
        }

        // IDEA no longer automatically downloads sources and javadocs.
        target.pluginManager.withPlugin("idea") {
            target.configure<IdeaModel> {
                module {
                    isDownloadJavadoc = true
                    isDownloadSources = true
                }
            }
        }

        // Add the necessary repositories.
        applyDefaultRepositories(target.repositories)

        // Apply a default java plugin configuration
        applyJavaSettings(target)

        applyMetadataStringReplacements(target)

        createProxyConfigurations(target, target.extensions.getByType<SourceSetContainer>().getByName(SourceSet.MAIN_SOURCE_SET_NAME))

        target.configurations.create("modstitchJiJ") {
            isTransitive = false

            configureJiJConfiguration(target, this)
        }
    }

    /**
     * Add all repositories necessary for the platform in here.
     * For example, Fabric will add fabric-maven to this.
     */
    protected open fun applyDefaultRepositories(repositories: RepositoryHandler) {
        repositories.mavenCentral()
        repositories.maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
        repositories.exclusiveContent {
            forRepository {
                repositories.maven("https://api.modrinth.com/maven") { name = "Modrinth" }
            }
            filter {
                includeGroup("maven.modrinth")
            }
        }
        repositories.exclusiveContent {
            forRepository {
                repositories.maven("https://cursemaven.com") { name = "Cursemaven" }
            }
            filter {
                includeGroup("curse.maven")
            }
        }
    }

    protected open fun applyJavaSettings(target: Project) {
        target.tasks.withType<JavaCompile> {
            options.encoding = "UTF-8"
        }

        target.pluginManager.withPlugin("plugin") {
            target.extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(target.modstitch.javaTarget.map { JavaLanguageVersion.of(it) })
            }
        }
    }

    /**
     * Ensures templates in files like fabric.mod.json are replaced.
     * e.g. ${mod_id} -> my_mod
     */
    protected open fun applyMetadataStringReplacements(target: Project): TaskProvider<ProcessResources> {
        val mainSourceSet = target.extensions.getByType<SourceSetContainer>()["main"]

        // Create a new `templates` directory set in the main sourceSet
        val templates = target.objects.sourceDirectorySet("templates", "Mod metadata resource templates")
        templates.srcDir("src/main/templates")
        mainSourceSet.extensions.add("templates", templates)

        val modstitch = target.modstitch

        // An alternative to the traditional `processResources` setup that is compatible with
        // IDE-managed runs (e.g. IntelliJ non-delegated build)
        val generateModMetadata by target.tasks.registering(ProcessResources::class) {
            val manifest = modstitch.metadata
            val mixin = modstitch.mixin

            val baseProperties = mapOf<String, Provider<String>>(
                "minecraft_version" to modstitch.minecraftVersion,
                "mod_version" to manifest.modVersion,
                "mod_name" to manifest.modName,
                "mod_id" to manifest.modId,
                "mod_license" to manifest.modLicense,
                "mod_description" to manifest.modDescription,
                "mod_group" to manifest.modGroup,
                "mod_author" to manifest.modAuthor,
                "mod_credits" to manifest.modCredits,
                "mod_mixins" to mixin.serializer.map { it.apply(mixin.configs.stream().toList()) }
            )

            // Combine the lazy-valued base properties with the replacement properties, lazily
            val allProperties = manifest.replacementProperties.map { replacementProperties ->
                baseProperties.mapValues { (_, value) -> value.get() } + replacementProperties
            }

            // Lazily provide the inputs
            inputs.property("allProperties", allProperties)

            // Expand lazily resolved properties only during execution
            doFirst {
                val resourcedProperties = allProperties.get()
                expand(resourcedProperties)
            }

            from(templates)
            into("build/generated/sources/modMetadata")

            exclude { fileTreeElement ->
                // At execution time, modLoaderManifest should be resolvable
                val currentManifest = modstitch.modLoaderManifest.orNull
                // Now build the set of manifests to exclude dynamically
                val manifestsToExclude = Platform.allModManifests - currentManifest
                // Return true if the file should be excluded, false otherwise
                fileTreeElement.name in manifestsToExclude
            }
        }
        // Include the output of "generateModMetadata" as an input directory for the build
        // This allows the funny dest dir (`generated/sources/modMetadata`) to be included in the root of the build
        mainSourceSet.resources.srcDir(generateModMetadata)

        return generateModMetadata
    }

    open fun createProxyConfigurations(target: Project, sourceSet: SourceSet) {
        fun mainOnly(configurationName: String): String? =
            configurationName.takeIf { sourceSet.name == SourceSet.MAIN_SOURCE_SET_NAME }

        listOfNotNull(
            mainOnly(sourceSet.apiConfigurationName),
            sourceSet.implementationConfigurationName,
            sourceSet.compileOnlyConfigurationName,
            sourceSet.runtimeOnlyConfigurationName,
            mainOnly(sourceSet.compileOnlyApiConfigurationName),
            mainOnly(Constants.Configurations.LOCAL_RUNTIME),
        ).forEach {
            createProxyConfigurations(target, target.configurations.getByName(it))
        }
    }
    abstract fun createProxyConfigurations(target: Project, configuration: Configuration)

    abstract fun configureJiJConfiguration(target: Project, configuration: Configuration)

}