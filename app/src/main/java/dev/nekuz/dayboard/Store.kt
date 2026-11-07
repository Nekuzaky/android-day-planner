package dev.nekuz.dayboard

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class StoreState(
    val loaded: Boolean = false,
    val board: Board = Board(),
    val saveFailed: Boolean = false,
    val storageBlocked: Boolean = false,
    val corruptNames: List<String> = emptyList(),
    val importCandidate: Board? = null,
    val importError: String? = null,
)

object Store {
    private const val DIRECTORY = "board"
    private const val FLUSH_MILLIS = 500L

    private val executor = Executors.newSingleThreadExecutor()
    private val io = Executors.newCachedThreadPool()
    private val importToken = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    private val pending = AtomicInteger()
    private var file: BoardFile? = null

    @Volatile
    var state = StoreState()
        private set

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun start(context: Context) = execute(context) { }

    fun update(context: Context, change: (Board) -> Board) = execute(context) { app ->
        val before = state.board
        val after = change(before)
        if (after != before) commit(app, after)
    }

    fun retrySave(context: Context) = execute(context) { app -> commit(app, state.board) }

    fun resync(context: Context, force: Boolean, done: (() -> Unit)? = null) = execute(context, done) { app ->
        if (!state.storageBlocked) {
            if (force) reconcile(app, true) else syncAlarms(app, state.board, false)
        }
    }

    fun fire(context: Context, taskId: Int, done: () -> Unit) = execute(context, done) { app ->
        val now = Instant.now()
        val zone = Device.zone()
        val task = state.board.tasks.firstOrNull { it.id == taskId }
        if (task != null && Reminders.shouldFire(task, now, zone)) {
            if (Notifications.enabled(app)) {
                commit(app, Boards.markFired(state.board, taskId, now, zone))
                Notifications.show(app, task, state.saveFailed)
            }
        } else if (!state.storageBlocked) {
            syncAlarms(app, state.board, false)
        }
    }

    fun readImport(context: Context, uri: Uri) {
        val app = context.applicationContext
        val token = importToken.incrementAndGet()
        io.execute {
            val decoded = parseImport(app, uri)
            if (token == importToken.get()) {
                execute(app) {
                    if (token == importToken.get()) {
                        state = when (decoded) {
                            is Decoded.Ok -> state.copy(importCandidate = decoded.board, importError = null)
                            is Decoded.Failed -> state.copy(importCandidate = null, importError = decoded.reason)
                        }
                    }
                }
            }
        }
    }

    private fun parseImport(app: Context, uri: Uri): Decoded = try {
        val stream = app.contentResolver.openInputStream(uri)
        if (stream == null) {
            Decoded.Failed(app.getString(R.string.import_unreadable))
        } else {
            val bytes = stream.use { BoundedIo.read(it, Limits.MAX_FILE_BYTES) }
            val text = bytes?.let(BoundedIo::utf8)
            when {
                bytes == null -> Decoded.Failed(app.getString(R.string.import_too_large))
                text == null -> Decoded.Failed(app.getString(R.string.import_not_text))
                else -> when (val decoded = BoardCodec.decode(text)) {
                    is Decoded.Ok -> decoded
                    is Decoded.Failed -> Decoded.Failed(ForeignText.clean(decoded.reason))
                }
            }
        }
    } catch (e: Exception) {
        Decoded.Failed(app.getString(R.string.import_unreadable))
    } catch (e: OutOfMemoryError) {
        Decoded.Failed(app.getString(R.string.import_too_large))
    }

    fun confirmImport(context: Context, candidate: Board) = execute(context) { app ->
        if (state.importCandidate === candidate) {
            state = state.copy(importCandidate = null)
            commit(app, Boards.prepareImported(candidate, Instant.now(), Device.zone()))
        }
    }

    fun clearImport(context: Context) = execute(context) {
        state = state.copy(importCandidate = null, importError = null)
    }

    fun export(context: Context, uri: Uri) = execute(context) { app ->
        val board = state.board
        val blocked = state.storageBlocked
        io.execute {
            val done = !blocked && writeDocument(app, uri) {
                it.write(BoardCodec.encode(board).toByteArray(Charsets.UTF_8))
            }
            toast(app, if (done) R.string.export_done else R.string.export_failed)
        }
    }

    fun exportCorrupt(context: Context, name: String, uri: Uri) = execute(context) { app ->
        val source = file?.corruptFile(name)
        io.execute {
            val done = source != null && writeDocument(app, uri) { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            toast(app, if (done) R.string.export_done else R.string.export_failed)
        }
    }

    private fun writeDocument(app: Context, uri: Uri, write: (OutputStream) -> Unit): Boolean = try {
        val stream = app.contentResolver.openOutputStream(uri, "wt")
        if (stream == null) {
            false
        } else {
            stream.use(write)
            true
        }
    } catch (e: Exception) {
        false
    } catch (e: OutOfMemoryError) {
        false
    }

    fun deleteCorrupt(context: Context, name: String) = execute(context) {
        val current = file
        if (current != null) {
            current.deleteCorrupt(name)
            state = state.copy(corruptNames = current.corruptNames())
        }
    }

    fun flush() {
        if (pending.get() == 0) return
        try {
            executor.submit { }.get(FLUSH_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            return
        }
    }

    private fun execute(context: Context, done: (() -> Unit)? = null, work: (Context) -> Unit) {
        val app = context.applicationContext
        pending.incrementAndGet()
        executor.execute {
            try {
                ensureLoaded(app)
                work(app)
            } catch (e: Exception) {
                state = state.copy(saveFailed = true, loaded = true)
            } catch (e: OutOfMemoryError) {
                state = state.copy(saveFailed = true, loaded = true)
            } finally {
                pending.decrementAndGet()
                publish()
                done?.invoke()
            }
        }
    }

    private fun ensureLoaded(app: Context) {
        if (file != null) return
        val directory = directory(app)
        val opened = BoardFile(directory)
        val load = opened.load(System.currentTimeMillis())
        file = opened
        val board = (load as? BoardLoad.Ok)?.board ?: Board()
        state = state.copy(
            loaded = true,
            board = board,
            storageBlocked = load is BoardLoad.Blocked,
            corruptNames = opened.corruptNames(),
        )
        Notifications.ensureChannel(app)
        if (load !is BoardLoad.Blocked) reconcile(app, true)
    }

    private fun reconcile(app: Context, force: Boolean) {
        val board = state.board
        val repaired = Boards.repairClock(board, Instant.now(), Device.zone())
        if (repaired != board) commit(app, repaired)
        syncAlarms(app, state.board, force)
    }

    private fun commit(app: Context, after: Board) {
        val before = state.board
        val saved = file?.save(after) ?: false
        state = state.copy(board = after, saveFailed = !saved)
        if (state.storageBlocked) return
        syncAlarms(app, after, false)
        Notifications.cancel(app, Reminders.staleNotifications(before.tasks, after.tasks))
    }

    private fun syncAlarms(app: Context, board: Board, force: Boolean) {
        try {
            Alarms.sync(app, directory(app), board, force)
        } catch (e: RuntimeException) {
            return
        }
    }

    private fun directory(app: Context) = File(app.filesDir, DIRECTORY)

    private fun toast(app: Context, text: Int) {
        main.post { Actions.toast(app, text) }
    }

    private fun publish() {
        main.post { listeners.toList().forEach { it() } }
    }
}
