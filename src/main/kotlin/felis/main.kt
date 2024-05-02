package felis

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.enum
import felis.meta.Mod
import felis.meta.ModDiscoverer
import felis.meta.ModMeta
import felis.side.Side
import felis.transformer.*
import felis.side.SideStrippingTransformation
import kotlinx.serialization.decodeFromString
import net.peanuuutz.tomlkt.Toml
import org.slf4j.LoggerFactory

import java.nio.file.Paths
import java.lang.invoke.MethodType
import java.lang.invoke.MethodHandles

import org.objectweb.asm.Type
import org.slf4j.Logger
import java.io.*
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.*
import javax.tools.*
import kotlin.io.path.createParentDirectories
import kotlin.io.path.extension
import kotlin.io.path.pathString

/**
 * Entrypoint called right after the [ModLoader] object has been initialized for this launch.
 * @see ModLoader.initLoader to see what that entails
 *
 * @author 0xJoeMama
 */
fun interface LoaderPluginEntrypoint {
    fun onLoaderInit()
}

/**
 * The central object of the mod loading process.
 * This object is first created at startup, initialized only once through the [ModLoaderCommand] class,
 * and then used for the rest of the loading process.
 *
 * @author 0xJoeMama
 */
// TODO/Missing Features for initial release
// 1. Useful mod metadata entries
// 2. Access wideners
// 3. JarInJar
object ModLoader {
    /**
     * An instance of a TOML object used for deserialization of mod metadata.
     */
    val toml = Toml {
        ignoreUnknownKeys = true
    }

    /**
     * Logger
     */
    private val logger: Logger = LoggerFactory.getLogger(ModLoader::class.java)

    /**
     * A language adapter, that contains all other language adapters.
     * Used when trying to call an entrypoint.
     *
     * @see LanguageAdapter
     */
    lateinit var languageAdapter: DelegatingLanguageAdapter
        private set

    /**
     * The discoverer created by the loader, loads mods passed through the command line.
     *
     * @see ModDiscoverer for detail of the discovery process
     */
    lateinit var discoverer: ModDiscoverer
        private set

    /**
     * A [JarContentCollection] referring to the game jar the loader is running on.
     *
     * @see ContentCollection for more info on [ContentCollection]s
     */
    lateinit var gameJar: JarContentCollection
        private set

    /**
     * The classloading brain of the whole loader
     *
     * @see TransformingClassLoader
     */
    lateinit var classLoader: TransformingClassLoader
        private set

    /**
     * Central transformation, containing all others as children
     *
     * @see Transformer
     */
    lateinit var transformer: Transformer
        private set

    /**
     * The **physical** side the loader is running on.
     * Physical side, refers to the jar distribution used.
     * [Side.CLIENT] means the client jar downloaded by the launcher.
     * [Side.SERVER] means the server jar downloaded through the minecraft website
     *
     * @see Side for more info on sides
     * @see SideStrippingTransformation for more info on what a specific [Side] entails
     */
    lateinit var side: Side
        private set

    /**
     * Initialize this loader object.
     *
     * @param mods a list of all jars this loader will attempt to load as mods. See [ModDiscoverer] for more info on how mods are loaded
     * @param sourceJar the game this loader is running from
     * @param side the **physical** side the loader is running on
     */
    fun initLoader(mods: List<String>, sourceJar: String, side: Side) {
        this.logger.info("starting mod loader")
        this.side = side // the physical side we are running on
        this.discoverer = ModDiscoverer(mods) // the object used to locate and initialize mods

        this.gameJar = JarContentCollection(Paths.get(sourceJar)) // the jar the game is located in
        this.languageAdapter = DelegatingLanguageAdapter() // tool used to create instances of abstract objects
        this.transformer = Transformer() // tool that transforms classes passed into it using registered Transformations
        this.classLoader = TransformingClassLoader() // the class loader that uses everything in here to work

        // register ourselves as a built-in mod
        this.discoverer.registerMod(
            Mod(
                JarContentCollection(Paths.get(ModLoader.javaClass.protectionDomain.codeSource.location.toURI())),
                classLoader.getResourceAsStream("loader.toml")
                    ?.use { String(it.readAllBytes()) }
                    ?.let { toml.decodeFromString<ModMeta>(it) }
                    ?: throw FileNotFoundException("File loader.toml was not found")
            )
        )

        // register out language adapters
        this.languageAdapter.apply {
            registerAdapter(KotlinLanguageAdapter)
            registerAdapter(JavaLanguageAdapter)
        }

        // Register built-in transformations of the loader itself
        this.transformer.apply {
            registerTransformation(SideStrippingTransformation)
        }

        // call all loader plugin entrypoints after we set ourselves up
        this.callEntrypoint("loader_plugin", LoaderPluginEntrypoint::onLoaderInit)
        // TODO: Lock languageAdapter transformer and discoverer registration methods after the entrypoint has been called
    }

    /**
     * Run a specific *main* method as specified by the Java Standard.
     *
     * @param owner the name of the class that contains the method
     * @param method the name of the method. Usually this would be `main`
     * @param desc the descriptor of the method. Usually this would be `([Ljava/lang/String;)V`
     * @param params the arguments passed into the method
     */
    fun call(owner: String, method: String, desc: String, params: Array<String>) {
        this.logger.info("starting game")
        this.logger.debug("target game jars: {}", gameJar.path)
        this.logger.debug("game args: ${params.contentToString()}")

        // create a mod list.
        // TODO: Perhaps make this prettier in the future
        val sw = StringWriter()
        sw.append("mods currently running: ")
        discoverer.forEach {
            sw.appendLine()
            sw.append("- ${it.meta.modid}: ${it.meta.version}")
        }
        this.logger.info(sw.toString())

        val mainClass = classLoader.loadClass(owner)
        // using MethodLookup because technically speaking it's better that reflection
        val mainMethod = MethodHandles.lookup().findStatic(
            mainClass,
            method,
            MethodType.fromMethodDescriptorString(desc, null)
        )

        // finally call the method
        mainMethod.invokeExact(params)
    }

    /**
     * Force all game classes to be transformed.
     * Goes through all classes of the [ModLoader.gameJar] reads them, and then applies all registered [Transformation]s to them.
     * Outputs classes in the specified jar file.
     *
     * @param outputPath the path to a jar where transformed classes will be output
     */
    fun auditTransformations(outputPath: String) {
        this.logger.warn("Auditing game jar ${this.gameJar.path}")
        FileSystems.newFileSystem(Paths.get(outputPath), mapOf("create" to "true")).use { outputJar ->
            FileSystems.newFileSystem(this.gameJar.path).use { gameJar ->
                for (clazz in Files.walk(gameJar.getPath("/")).filter { it.extension == "class" }) {
                    val name = clazz.pathString.substring(1).replace("/", ".").removeSuffix(".class")
                    val oldBytes = Files.newInputStream(clazz).use { it.readBytes() }
                    val container = ClassContainer(name, oldBytes)
                    this.transformer.transform(container)

                    val output = outputJar.getPath("/").resolve(clazz.pathString)
                    this.logger.trace("Auditing $name")

                    output.createParentDirectories()
                    Files.newOutputStream(
                        output,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE
                    ).use { it.write(container.bytes) }
                }
            }
        }
    }

    /**
     * Call the entrypoint specified by the given id.
     * Entrypoint IDs don't need to be specifically registered.
     * Entrypoints need to be registered in the [ModMeta.entrypoints] list.
     *
     * @see ModMeta for the mod metadata schema
     *
     * @param T the type of the entrypoint
     * @param R the return type of the entrypoint method call
     * @param id the id of the entrypoint
     * @param method the method to run on objects of the [T] type
     *
     * @return a list of the result of calling all the entrypoints
     */
    @Suppress("MemberVisibilityCanBePrivate")
    inline fun <reified T, reified R> callEntrypoint(id: String, crossinline method: (T) -> R): List<R> =
        this.discoverer
            .asSequence()
            .flatMap { it.meta.entrypoints }
            .filter { it.id == id }
            .map { this.languageAdapter.createInstance<T>(it.path).getOrThrow() }
            .map { method(it) }
            .toList()
}

/**
 * Not meant to be used outside of this file.
 * Parses cli arguments and initializes the loader.
 */
internal class ModLoaderCommand : CliktCommand() {
    val mods: List<String> by option("--mods")
        .help("All directories and jar files to look for mods in separated by ':'")
        .split(File.pathSeparator)
        .default(emptyList())
    val gameJarPath: String by option("--source")
        .help("Define the source jar to run the game from")
        .required()
    val printClassPath: Boolean by option("--print-cp")
        .help("Print the jvm classpath")
        .boolean()
        .default(false)
    val side: Side by option("--side").enum<Side> { it.name }.required()
    val audit by option("--audit")
        .default("no")
        .help("Apply all transformations defined by mods to the source jar")

    // noop
    override fun run() = Unit
}

fun main(args: Array<String>) {
    println("Felis running using arguments: ${args.contentToString()}")
    /**
     *  delegate to [ModLoaderCommand]
     */
    val cmd = ModLoaderCommand()

    // Everything after -- is game arguments
    val ourArgs = args.takeWhile { it != "--" }
    // Using this since it clikt pollutes stacktraces big time
    cmd.parse(ourArgs)

    // print the classpath
    if (cmd.printClassPath) {
        val cp = System.getProperty("java.class.path").split(File.pathSeparator)
        for (s in cp) {
            println(s)
        }
    }

    // Initialize the ModLoader instance for this launch
    ModLoader.initLoader(
        mods = cmd.mods,
        sourceJar = cmd.gameJarPath,
        side = cmd.side,
    )

    // Audit game classes if that is what the user chose
    if (cmd.audit != "no") {
        ModLoader.auditTransformations(cmd.audit)
        return
    }

    // locate main class
    val mainClass = when (ModLoader.side) {
        Side.CLIENT -> "net.minecraft.client.main.Main"
        Side.SERVER -> "net.minecraft.server.Main"
    }

    // start the game using the main class from above
    ModLoader.call(
        owner = mainClass,
        method = "main",
        desc = Type.getMethodDescriptor(Type.getType(Void.TYPE), Type.getType(Array<String>::class.java)),
        params = args.takeLastWhile { it != "--" }.toTypedArray()
    )
}
