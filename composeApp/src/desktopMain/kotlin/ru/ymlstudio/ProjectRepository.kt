package ru.ymlstudio

import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.*

fun newId(): String = UUID.randomUUID().toString()
fun defaultDataDirectory(): Path {
    System.getenv("YML_STUDIO_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
    val home = Path.of(System.getProperty("user.home"))
    return when {
        System.getProperty("os.name").startsWith("Windows") -> Path.of(System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString()).resolve("YMLStudio/data")
        System.getProperty("os.name").startsWith("Mac") -> home.resolve("Library/Application Support/YMLStudio/data")
        else -> home.resolve(".local/share/YMLStudio/data")
    }
}

class ProjectRepository(val directory: Path) : Closeable {
    private val channel: FileChannel
    private val lock: java.nio.channels.FileLock
    init {
        Files.createDirectories(directory)
        Files.createDirectories(directory.resolve("images"))
        channel = FileChannel.open(directory.resolve(".desktop.lock"), CREATE, WRITE)
        lock = try { channel.tryLock() ?: error("Этот каталог уже открыт в другом экземпляре YML Студии.") }
        catch (e: Exception) { channel.close(); throw IllegalStateException("Не удалось открыть каталог: возможно, он уже используется другим экземпляром.", e) }
    }
    fun load(): Project {
        val path = directory.resolve("project.json")
        if (!path.exists()) return defaultProject()
        return decode(path)
    }
    private fun decode(path: Path): Project {
        require(Files.size(path) <= 50 * 1024 * 1024) { "Проект больше 50 МБ" }
        return projectJson.decodeFromString<Project>(path.readText()).also(::checkShape).withDeliveryDaysMapping()
    }
    fun save(project: Project) {
        checkShape(project)
        atomicWrite(directory.resolve("project.json")) { it.write(projectJson.encodeToString(project).toByteArray(Charsets.UTF_8)) }
    }
    fun addImage(source: Path): Picture {
        require(Files.size(source) in 1..15L * 1024 * 1024) { "Размер фотографии должен быть не больше 15 МБ" }
        ImageIO.createImageInputStream(source.toFile()).use { input ->
            require(input != null) { "Не удалось прочитать изображение" }
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Разрешены только JPEG и PNG" }
            val reader = readers.next()
            try {
                val format = reader.formatName.lowercase()
                require(format in listOf("jpeg", "jpg", "png")) { "Разрешены только JPEG и PNG" }
                reader.input = input
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width in 250..3500 && height in 250..3500) { "Размеры изображения должны быть от 250 до 3500 пикселей по каждой стороне" }
                reader.read(0) // Reject damaged files, after checking dimensions to bound memory use.
                val filename = newId().replace("-", "") + if (format == "png") ".png" else ".jpg"
                Files.copy(source, directory.resolve("images").resolve(filename))
                return Picture(file = filename, width = width, height = height, name = source.fileName.toString())
            } finally { reader.dispose() }
        }
    }
    private fun imageFiles(project: Project): List<Path> = project.products.flatMap { it.pictures }.map { it.file }.filter { it.isNotEmpty() }.distinct().map {
        directory.resolve("images").resolve(it).also { path -> require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) { "Не найден файл фотографии: $it" } }
    }
    fun export(project: Project, destination: Path, bundle: Boolean, allowInvalid: Boolean = false) {
        val yml = buildYml(project, allowInvalid = allowInvalid).toByteArray(Charsets.UTF_8)
        val images = if (bundle) imageFiles(project) else emptyList()
        protectDestination(destination)
        atomicWrite(destination) { output ->
            if (!bundle) output.write(yml)
            else ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("catalog.yml")); zip.write(yml); zip.closeEntry()
                images.forEach { zip.putNextEntry(ZipEntry("images/${it.fileName}")); Files.copy(it, zip); zip.closeEntry() }
            }
        }
    }
    fun backup(destination: Path) {
        val project = load()
        protectDestination(destination)
        atomicWrite(destination) { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("project.json")); zip.write(projectJson.encodeToString(project).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            Files.list(directory.resolve("images")).use { paths -> paths.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }.sorted().forEach {
                zip.putNextEntry(ZipEntry("images/${it.fileName}")); Files.copy(it, zip); zip.closeEntry()
            } }
        } }
    }
    /** Import the original version-1 JSON and its sibling images directory. Existing data is backed up first. */
    fun importProject(source: Path): Project {
        val project = decode(source)
        if (source.toAbsolutePath().normalize() == directory.resolve("project.json").toAbsolutePath().normalize()) return project
        val mapping = mutableMapOf<String, String>()
        val sources = (project.products.flatMap { it.pictures } + project.templates.flatMap { it.defaultPictures }).map { it.file }.filter { it.isNotEmpty() }.distinct().associateWith {
            source.parent.resolve("images").resolve(it).also { path -> require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) { "Рядом с проектом не найдено фото images/$it" } }
        }
        // Never overwrite images used by the current project, even when importing another copy of it.
        backup(directory.resolve("backup-before-import-${System.currentTimeMillis()}.zip"))
        sources.forEach { (name, path) ->
            val next = newId().replace("-", "") + "." + name.substringAfterLast('.')
            Files.copy(path, directory.resolve("images").resolve(next))
            mapping[name] = next
        }
        fun remap(pictures: List<Picture>) = pictures.map { it.copy(file = mapping[it.file] ?: it.file) }
        val imported = project.copy(products = project.products.map { p -> p.copy(pictures = remap(p.pictures)) },
            templates = project.templates.map { t -> t.copy(defaultPictures = remap(t.defaultPictures)) })
        save(imported)
        return imported
    }
    private fun protectDestination(destination: Path) {
        val path = destination.toAbsolutePath().normalize()
        val root = directory.toAbsolutePath().normalize()
        require(path != root.resolve("project.json") && path != root.resolve(".desktop.lock") && !path.startsWith(root.resolve("images"))) { "Выберите файл вне рабочих данных проекта" }
    }
    override fun close() { lock.release(); channel.close() }
}

private fun atomicWrite(destination: Path, write: (java.io.OutputStream) -> Unit) {
    val target = destination.toAbsolutePath()
    Files.createDirectories(target.parent)
    val temp = Files.createTempFile(target.parent, ".ymlstudio-", ".tmp")
    try {
        Files.newOutputStream(temp).use(write)
        FileChannel.open(temp, WRITE).use { it.force(true) }
        try { Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp, target, REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temp) }
}
