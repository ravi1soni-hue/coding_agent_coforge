package com.coforge.codingagent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.*
import java.net.URI
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal LSP client for the Dart Analysis Server.
 * Spawns `dart language-server --protocol=lsp` as a subprocess and communicates
 * over stdin/stdout using the Language Server Protocol (JSON-RPC 2.0).
 *
 * Capabilities used:
 *   - textDocument/hover     → type information for the token under cursor
 *   - textDocument/definition → definition location for go-to-definition
 *
 * Only starts if the Dart executable is found; gracefully no-ops otherwise.
 * Disposed (process killed) when the project closes.
 */
@Service(Service.Level.PROJECT)
class DartLspService(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(DartLspService::class.java)
    private val gson = Gson()
    private val idCounter = AtomicInteger(1)

    @Volatile private var process: Process? = null
    @Volatile private var initialized = false
    private val pendingRequests = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private var readerThread: Thread? = null

    // Lazy start — only called when first needed (Flutter project context is requested)
    fun ensureStarted(): Boolean {
        if (initialized && process?.isAlive == true) return true
        if (process?.isAlive == true) return false  // starting in progress
        val dartExe = findDartExecutable() ?: return false
        return try {
            val pb = ProcessBuilder(dartExe, "language-server", "--protocol=lsp")
                .directory(File(project.basePath ?: "."))
                .redirectErrorStream(false)
            process = pb.start()
            startReaderThread()
            sendInitialize()
            true
        } catch (e: Exception) {
            LOG.warn("Failed to start Dart Analysis Server: ${e.message}")
            false
        }
    }

    private fun findDartExecutable(): String? {
        val isWindows = System.getProperty("os.name", "").contains("Win", ignoreCase = true)
        val exe = if (isWindows) "dart.exe" else "dart"

        // 1. Environment variables pointing at Dart/Flutter SDK roots
        listOf("FLUTTER_ROOT", "DART_SDK", "DART_HOME").forEach { env ->
            System.getenv(env)?.let { root ->
                val f = File(root, "bin/$exe")
                if (f.exists() && f.canExecute()) return f.absolutePath
            }
        }

        // 2. Flutter SDK path from local.properties (Android projects with Flutter module)
        project.basePath?.let { base ->
            val props = File(base, "local.properties")
            if (props.exists()) {
                props.readLines().firstOrNull { it.startsWith("flutter.sdk=") }
                    ?.substringAfter("=")?.trim()
                    ?.let { sdk -> File(sdk, "bin/$exe").takeIf { it.exists() }?.absolutePath }
                    ?.let { return it }
            }
        }

        // 3. System PATH
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparator)
            .asSequence()
            .map { File(it, exe) }
            .firstOrNull { it.exists() && it.canExecute() }
            ?.absolutePath
    }

    private fun startReaderThread() {
        val proc = process ?: return
        readerThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                while (!Thread.currentThread().isInterrupted && proc.isAlive) {
                    var contentLength = -1
                    // Read headers until blank line
                    var line = reader.readLine() ?: break
                    while (line.isNotEmpty()) {
                        if (line.startsWith("Content-Length:", ignoreCase = true))
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                        line = reader.readLine() ?: break
                    }
                    if (contentLength <= 0) continue
                    // Read exactly contentLength UTF-8 chars
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(chars, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    handleMessage(String(chars, 0, read))
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "DartLSP-Reader-${project.name}"; start() }
    }

    private fun handleMessage(json: String) {
        try {
            val obj = gson.fromJson(json, JsonObject::class.java) ?: return
            val id = obj["id"]?.takeIf { !it.isJsonNull }?.asInt
            if (id != null) {
                pendingRequests.remove(id)?.complete(obj)
            } else if (obj["method"]?.asString == "initialized") {
                initialized = true
            }
        } catch (_: Exception) {}
    }

    private fun sendInitialize() {
        val id = idCounter.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pendingRequests[id] = future

        val rootUri = project.basePath?.let { "file://${it.replace("\\", "/")}" } ?: "file:///"
        sendRaw(buildRequest(id, "initialize", mapOf(
            "processId" to ProcessHandle.current().pid().toInt(),
            "rootUri" to rootUri,
            "capabilities" to mapOf(
                "textDocument" to mapOf(
                    "hover" to mapOf("contentFormat" to listOf("plaintext")),
                    "definition" to mapOf("linkSupport" to false)
                )
            ),
            "initializationOptions" to emptyMap<String, Any>()
        )))

        // Wait for initialize result, then send initialized notification
        Thread {
            try {
                future.get(10, TimeUnit.SECONDS)
                sendRaw(buildNotification("initialized", emptyMap<String, Any>()))
                initialized = true
            } catch (_: Exception) { initialized = false }
        }.apply { isDaemon = true; start() }
    }

    /**
     * Get hover type information for a position in a Dart file.
     * Returns the hover text (type + doc) or null if unavailable.
     */
    fun hover(filePath: String, line: Int, character: Int, timeoutMs: Long = 2500): String? {
        if (!initialized || process?.isAlive != true) return null
        val id = idCounter.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pendingRequests[id] = future

        val fileUri = "file://${filePath.replace("\\", "/")}"
        sendRaw(buildRequest(id, "textDocument/hover", mapOf(
            "textDocument" to mapOf("uri" to fileUri),
            "position"     to mapOf("line" to line, "character" to character)
        )))

        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val contents = result.getAsJsonObject("result")?.get("contents") ?: return null
            when {
                contents.isJsonObject -> contents.asJsonObject.get("value")?.asString
                contents.isJsonArray  -> contents.asJsonArray.firstOrNull()?.asJsonObject?.get("value")?.asString
                else                  -> contents.asString
            }?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null } finally { pendingRequests.remove(id) }
    }

    /**
     * Get definition location for a symbol.
     * Returns a human-readable "file:line" string or null.
     */
    fun definition(filePath: String, line: Int, character: Int, timeoutMs: Long = 2500): String? {
        if (!initialized || process?.isAlive != true) return null
        val id = idCounter.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pendingRequests[id] = future

        val fileUri = "file://${filePath.replace("\\", "/")}"
        sendRaw(buildRequest(id, "textDocument/definition", mapOf(
            "textDocument" to mapOf("uri" to fileUri),
            "position"     to mapOf("line" to line, "character" to character)
        )))

        return try {
            val result = future.get(timeoutMs, TimeUnit.MILLISECONDS).getAsJsonObject("result") ?: return null
            // result can be a Location object or array of Location
            val loc = if (result.isJsonArray && result.asJsonArray.size() > 0)
                result.asJsonArray[0].asJsonObject else result
            val uri = loc.get("uri")?.asString?.removePrefix("file://") ?: return null
            val startLine = loc.getAsJsonObject("range")?.getAsJsonObject("start")?.get("line")?.asInt ?: 0
            "$uri:${startLine + 1}"
        } catch (_: Exception) { null } finally { pendingRequests.remove(id) }
    }

    private fun buildRequest(id: Int, method: String, params: Any): String =
        gson.toJson(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))

    private fun buildNotification(method: String, params: Any): String =
        gson.toJson(mapOf("jsonrpc" to "2.0", "method" to method, "params" to params))

    private fun sendRaw(json: String) {
        val proc = process ?: return
        try {
            val bytes = json.toByteArray(Charsets.UTF_8)
            val header = "Content-Length: ${bytes.size}\r\n\r\n"
            synchronized(proc.outputStream) {
                proc.outputStream.write(header.toByteArray(Charsets.UTF_8))
                proc.outputStream.write(bytes)
                proc.outputStream.flush()
            }
        } catch (e: Exception) {
            LOG.debug("DartLSP write error: ${e.message}")
        }
    }

    override fun dispose() {
        pendingRequests.values.forEach { it.cancel(true) }
        pendingRequests.clear()
        readerThread?.interrupt()
        try {
            process?.let { proc ->
                sendRaw(buildNotification("exit", emptyMap<String, Any>()))
                Thread.sleep(200)
                proc.destroy()
            }
        } catch (_: Exception) {}
        process = null
        initialized = false
    }

    companion object {
        fun getInstance(project: Project): DartLspService =
            project.getService(DartLspService::class.java)
    }
}
