package dev.nucleusframework.tray.linux

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.ByteByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A D-Bus client for the tests, over the same sd-bus the tray uses (`libsystemd.so.0`, JNA).
 *
 * The tests play the panel: they read the item's properties and menu, and call `Activate`,
 * `Event`, `AboutToShow` as a host does. Each [SdBus] is one connection driven by its own thread
 * (sd-bus objects are single-threaded); every public call hops onto it.
 */
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal interface LibSystemd : Library {
    fun sd_bus_new(ret: PointerByReference): Int

    fun sd_bus_set_address(
        bus: Pointer,
        address: String,
    ): Int

    fun sd_bus_set_bus_client(
        bus: Pointer,
        b: Int,
    ): Int

    fun sd_bus_start(bus: Pointer): Int

    fun sd_bus_flush_close_unref(bus: Pointer): Pointer?

    fun sd_bus_process(
        bus: Pointer,
        ret: Pointer?,
    ): Int

    fun sd_bus_wait(
        bus: Pointer,
        timeoutUsec: Long,
    ): Int

    fun sd_bus_get_unique_name(
        bus: Pointer,
        name: PointerByReference,
    ): Int

    fun sd_bus_request_name(
        bus: Pointer,
        name: String,
        flags: Long,
    ): Int

    fun sd_bus_message_new_method_call(
        bus: Pointer,
        m: PointerByReference,
        destination: String?,
        path: String,
        iface: String?,
        member: String,
    ): Int

    fun sd_bus_message_new_method_return(
        call: Pointer,
        m: PointerByReference,
    ): Int

    fun sd_bus_call(
        bus: Pointer,
        m: Pointer,
        usec: Long,
        error: Pointer,
        reply: PointerByReference,
    ): Int

    fun sd_bus_send(
        bus: Pointer?,
        m: Pointer,
        cookie: LongByReference?,
    ): Int

    fun sd_bus_message_unref(m: Pointer): Pointer?

    fun sd_bus_message_append_basic(
        m: Pointer,
        type: Byte,
        p: Pointer,
    ): Int

    fun sd_bus_message_open_container(
        m: Pointer,
        type: Byte,
        contents: String,
    ): Int

    fun sd_bus_message_close_container(m: Pointer): Int

    fun sd_bus_message_peek_type(
        m: Pointer,
        type: ByteByReference,
        contents: PointerByReference,
    ): Int

    fun sd_bus_message_read_basic(
        m: Pointer,
        type: Byte,
        p: Pointer,
    ): Int

    fun sd_bus_message_read_array(
        m: Pointer,
        type: Byte,
        ptr: PointerByReference,
        size: LongByReference,
    ): Int

    fun sd_bus_message_enter_container(
        m: Pointer,
        type: Byte,
        contents: String?,
    ): Int

    fun sd_bus_message_exit_container(m: Pointer): Int

    fun sd_bus_message_get_member(m: Pointer): String?

    fun sd_bus_message_get_interface(m: Pointer): String?

    fun sd_bus_message_get_path(m: Pointer): String?

    fun sd_bus_message_get_sender(m: Pointer): String?

    fun sd_bus_message_get_type(
        m: Pointer,
        type: ByteByReference,
    ): Int

    fun sd_bus_add_match(
        bus: Pointer,
        slot: Pointer?,
        match: String,
        callback: MessageHandler,
        userdata: Pointer?,
    ): Int

    fun sd_bus_add_object(
        bus: Pointer,
        slot: Pointer?,
        path: String,
        callback: MessageHandler,
        userdata: Pointer?,
    ): Int

    /** `sd_bus_message_handler_t`. */
    fun interface MessageHandler : Callback {
        fun invoke(
            m: Pointer,
            userdata: Pointer?,
            error: Pointer?,
        ): Int
    }
}

/** A D-Bus error reply (or a local failure), with its D-Bus name. */
internal class DBusException(
    val name: String,
    message: String,
) : RuntimeException("$name: $message")

/** A value of a `v` (variant) slot, with its signature. */
internal data class Variant(
    val signature: String,
    val value: Any?,
)

/** A received signal. */
internal data class Signal(
    val sender: String?,
    val path: String?,
    val iface: String?,
    val member: String?,
    val args: List<Any?>,
    val atNanos: Long = System.nanoTime(),
)

internal class SdBus private constructor(
    address: String,
    label: String,
) : AutoCloseable {
    private val tasks = LinkedBlockingQueue<FutureTask<*>>()
    private val handlers = mutableListOf<LibSystemd.MessageHandler>()
    private lateinit var bus: Pointer

    @Volatile private var closed = false

    /** Every signal this connection subscribed to, in arrival order. */
    val signals = ConcurrentLinkedQueue<Signal>()

    private val thread =
        Thread({ loop() }, "SdBus-$label").apply {
            isDaemon = true
        }

    init {
        val ready =
            FutureTask {
                val ref = PointerByReference()
                check(lib.sd_bus_new(ref) >= 0)
                bus = ref.value
                check(lib.sd_bus_set_address(bus, address) >= 0)
                check(lib.sd_bus_set_bus_client(bus, 1) >= 0)
                val r = lib.sd_bus_start(bus)
                check(r >= 0) { "sd_bus_start($address) = $r" }
            }
        tasks += ready
        thread.start()
        ready.get(10, TimeUnit.SECONDS)
    }

    private fun loop() {
        while (!closed) {
            while (true) tasks.poll()?.run() ?: break
            if (!::bus.isInitialized) continue
            while (lib.sd_bus_process(bus, null) > 0) {
                while (true) tasks.poll()?.run() ?: break
            }
            lib.sd_bus_wait(bus, 2_000)
        }
        if (::bus.isInitialized) lib.sd_bus_flush_close_unref(bus)
    }

    /** Runs [block] on the connection's thread. */
    fun <T> onBus(block: () -> T): T {
        check(!closed) { "closed" }
        val task = FutureTask(Callable(block))
        tasks += task
        return try {
            task.get(30, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    val uniqueName: String
        get() =
            onBus {
                val ref = PointerByReference()
                check(lib.sd_bus_get_unique_name(bus, ref) >= 0)
                ref.value.getString(0)
            }

    /** Calls [member] and returns the reply's values. [args] follow [signature] (see [append]). */
    fun call(
        destination: String,
        path: String,
        iface: String,
        member: String,
        signature: String = "",
        vararg args: Any?,
        timeoutMs: Long = 5_000,
    ): List<Any?> =
        onBus {
            val mRef = PointerByReference()
            check(lib.sd_bus_message_new_method_call(bus, mRef, destination, path, iface, member) >= 0)
            val m = mRef.value
            try {
                appendAll(m, signature, args.toList())
                val error = Memory(32).apply { clear() }
                val reply = PointerByReference()
                val r = lib.sd_bus_call(bus, m, timeoutMs * 1000, error, reply)
                if (r < 0) {
                    val name = error.getPointer(0)?.getString(0) ?: "errno.$r"
                    val message = error.getPointer(8)?.getString(0, "UTF-8") ?: ""
                    throw DBusException(name, message)
                }
                try {
                    readAll(reply.value)
                } finally {
                    lib.sd_bus_message_unref(reply.value)
                }
            } finally {
                lib.sd_bus_message_unref(m)
            }
        }

    fun property(
        destination: String,
        path: String,
        iface: String,
        name: String,
    ): Any? =
        (call(destination, path, "org.freedesktop.DBus.Properties", "Get", "ss", iface, name).single() as Variant).value

    /** Records the signals matching [rule] into [signals]. */
    fun subscribe(rule: String) {
        onBus {
            val handler =
                LibSystemd.MessageHandler { m, _, _ ->
                    signals +=
                        Signal(
                            lib.sd_bus_message_get_sender(m),
                            lib.sd_bus_message_get_path(m),
                            lib.sd_bus_message_get_interface(m),
                            lib.sd_bus_message_get_member(m),
                            runCatching { readAll(m) }.getOrElse { emptyList() },
                        )
                    0
                }
            handlers += handler
            check(lib.sd_bus_add_match(bus, null, rule, handler, null) >= 0)
        }
    }

    /**
     * Serves method calls on [path]: [handle] gets (member, interface, sender, args) and returns
     * the reply as (signature, values), or null to leave the call to sd-bus (unknown method).
     */
    fun serve(
        path: String,
        handle: (member: String, iface: String?, sender: String?, args: List<Any?>) -> Pair<String, List<Any?>>?,
    ) {
        onBus {
            val handler =
                LibSystemd.MessageHandler { m, _, _ ->
                    val type = ByteByReference()
                    lib.sd_bus_message_get_type(m, type)
                    if (type.value.toInt() != 1) return@MessageHandler 0 // method calls only
                    val answer =
                        handle(
                            lib.sd_bus_message_get_member(m).orEmpty(),
                            lib.sd_bus_message_get_interface(m),
                            lib.sd_bus_message_get_sender(m),
                            readAll(m),
                        ) ?: return@MessageHandler 0
                    val reply = PointerByReference()
                    lib.sd_bus_message_new_method_return(m, reply)
                    appendAll(reply.value, answer.first, answer.second)
                    lib.sd_bus_send(null, reply.value, null)
                    lib.sd_bus_message_unref(reply.value)
                    1
                }
            handlers += handler
            check(lib.sd_bus_add_object(bus, null, path, handler, null) >= 0)
        }
    }

    fun requestName(name: String) {
        onBus {
            val r = lib.sd_bus_request_name(bus, name, 0)
            check(r >= 0) { "request_name($name) = $r" }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        thread.join(5_000)
    }

    // ------------------------------------------------------------------ encoding

    private fun appendAll(
        m: Pointer,
        signature: String,
        values: List<Any?>,
    ) {
        val types = splitSignature(signature)
        require(types.size == values.size) { "signature $signature for ${values.size} values" }
        types.zip(values).forEach { (t, v) -> append(m, t, v) }
    }

    private fun append(
        m: Pointer,
        type: String,
        value: Any?,
    ) {
        when (type[0]) {
            'a' -> {
                val element = type.substring(1)
                check(lib.sd_bus_message_open_container(m, 'a'.code.toByte(), element) >= 0)
                val items: Collection<Any?> =
                    when (value) {
                        is Map<*, *> -> value.entries.map { it.key to it.value }
                        is IntArray -> value.toList()
                        is Collection<*> -> value
                        else -> error("array value $value")
                    }
                for (item in items) append(m, element, item)
                check(lib.sd_bus_message_close_container(m) >= 0)
            }
            '(' -> {
                val inner = type.substring(1, type.length - 1)
                check(lib.sd_bus_message_open_container(m, 'r'.code.toByte(), inner) >= 0)
                appendAll(m, inner, value as List<Any?>)
                check(lib.sd_bus_message_close_container(m) >= 0)
            }
            '{' -> {
                val inner = type.substring(1, type.length - 1)
                check(lib.sd_bus_message_open_container(m, 'e'.code.toByte(), inner) >= 0)
                val (k, v) = value as Pair<*, *>
                appendAll(m, inner, listOf(k, v))
                check(lib.sd_bus_message_close_container(m) >= 0)
            }
            'v' -> {
                val variant = value as Variant
                check(lib.sd_bus_message_open_container(m, 'v'.code.toByte(), variant.signature) >= 0)
                append(m, variant.signature, variant.value)
                check(lib.sd_bus_message_close_container(m) >= 0)
            }
            else -> appendBasic(m, type[0], value)
        }
    }

    private fun appendBasic(
        m: Pointer,
        type: Char,
        value: Any?,
    ) {
        val mem = Memory(8).apply { clear() }
        var keep: Memory? = null
        when (type) {
            'y' -> mem.setByte(0, (value as Number).toByte())
            'b' -> mem.setInt(0, if (value as Boolean) 1 else 0)
            'n', 'q' -> mem.setShort(0, (value as Number).toShort())
            'i', 'u' -> mem.setInt(0, (value as Number).toInt())
            'x', 't' -> mem.setLong(0, (value as Number).toLong())
            'd' -> mem.setDouble(0, (value as Number).toDouble())
            's', 'o', 'g' -> {
                val bytes = (value as String).toByteArray(Charsets.UTF_8)
                keep =
                    Memory(bytes.size + 1L).apply {
                        write(0, bytes, 0, bytes.size)
                        setByte(bytes.size.toLong(), 0)
                    }
                val r = lib.sd_bus_message_append_basic(m, type.code.toByte(), keep)
                check(r >= 0) { "append '$type' $value = $r" }
                return
            }
            else -> error("type $type")
        }
        val r = lib.sd_bus_message_append_basic(m, type.code.toByte(), mem)
        check(r >= 0) { "append '$type' $value = $r" }
        keep?.close()
    }

    // ------------------------------------------------------------------ decoding

    private fun readAll(m: Pointer): List<Any?> {
        val out = ArrayList<Any?>()
        while (true) {
            val (type, contents) = peek(m) ?: break
            out += read(m, type, contents)
        }
        return out
    }

    private fun peek(m: Pointer): Pair<Char, String?>? {
        val type = ByteByReference()
        val contents = PointerByReference()
        val r = lib.sd_bus_message_peek_type(m, type, contents)
        check(r >= 0) { "peek = $r" }
        if (r == 0) return null
        return type.value.toInt().toChar() to contents.value?.getString(0)
    }

    private fun read(
        m: Pointer,
        type: Char,
        contents: String?,
    ): Any? =
        when (type) {
            'a' ->
                if (contents == "y") {
                    val ptr = PointerByReference()
                    val size = LongByReference()
                    check(lib.sd_bus_message_read_array(m, 'y'.code.toByte(), ptr, size) >= 0)
                    if (size.value == 0L) ByteArray(0) else ptr.value.getByteArray(0, size.value.toInt())
                } else {
                    check(lib.sd_bus_message_enter_container(m, 'a'.code.toByte(), contents) >= 0)
                    val items = readAll(m)
                    check(lib.sd_bus_message_exit_container(m) >= 0)
                    if (contents!!.startsWith("{")) items.associate { it as Pair<*, *> } else items
                }
            'r' -> {
                check(lib.sd_bus_message_enter_container(m, 'r'.code.toByte(), contents) >= 0)
                val fields = readAll(m)
                check(lib.sd_bus_message_exit_container(m) >= 0)
                fields
            }
            'e' -> {
                check(lib.sd_bus_message_enter_container(m, 'e'.code.toByte(), contents) >= 0)
                val (k, v) = readAll(m)
                check(lib.sd_bus_message_exit_container(m) >= 0)
                k to v
            }
            'v' -> {
                check(lib.sd_bus_message_enter_container(m, 'v'.code.toByte(), contents) >= 0)
                val value = readAll(m).single()
                check(lib.sd_bus_message_exit_container(m) >= 0)
                Variant(contents!!, value)
            }
            else -> {
                val mem = Memory(8).apply { clear() }
                check(lib.sd_bus_message_read_basic(m, type.code.toByte(), mem) >= 0)
                when (type) {
                    'y' -> mem.getByte(0).toInt() and 0xFF
                    'b' -> mem.getInt(0) != 0
                    'n' -> mem.getShort(0).toInt()
                    'q' -> mem.getShort(0).toInt() and 0xFFFF
                    'i', 'h' -> mem.getInt(0)
                    'u' -> mem.getInt(0).toLong() and 0xFFFFFFFFL
                    'x', 't' -> mem.getLong(0)
                    'd' -> mem.getDouble(0)
                    's', 'o', 'g' -> mem.getPointer(0).getString(0, "UTF-8")
                    else -> error("type $type")
                }
            }
        }

    companion object {
        val lib: LibSystemd by lazy { Native.load("systemd", LibSystemd::class.java) }

        fun connect(
            address: String,
            label: String,
        ): SdBus = SdBus(address, label)

        /** Splits a signature into its complete types. */
        fun splitSignature(signature: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < signature.length) {
                val end = completeTypeEnd(signature, i)
                out += signature.substring(i, end)
                i = end
            }
            return out
        }

        private fun completeTypeEnd(
            s: String,
            start: Int,
        ): Int =
            when (s[start]) {
                'a' -> completeTypeEnd(s, start + 1)
                '(', '{' -> {
                    var depth = 0
                    var i = start
                    while (true) {
                        when (s[i]) {
                            '(', '{' -> depth++
                            ')', '}' -> depth--
                        }
                        i++
                        if (depth == 0) break
                    }
                    i
                }
                else -> start + 1
            }
    }
}

/** A private session bus for the test JVM: `dbus-daemon --session` on a socket in a temp dir. */
internal class PrivateBus private constructor(
    private val process: Process,
    val address: String,
    private val dir: File,
) : AutoCloseable {
    val isAlive: Boolean get() = process.isAlive

    override fun close() {
        process.destroy()
        process.waitFor(5, TimeUnit.SECONDS)
        dir.deleteRecursively()
    }

    companion object {
        fun start(): PrivateBus {
            val dir =
                File(
                    System.getProperty("java.io.tmpdir"),
                    "nucleus-tray-bus-${System.nanoTime()}",
                ).apply { mkdirs() }
            val socket = File(dir, "bus")
            val process =
                ProcessBuilder(
                    "dbus-daemon",
                    "--session",
                    "--nofork",
                    "--nopidfile",
                    "--address=unix:path=${socket.absolutePath}",
                    "--print-address=1",
                ).redirectErrorStream(true).start()
            val address = process.inputStream.bufferedReader().readLine() ?: error("dbus-daemon printed no address")
            return PrivateBus(process, address.trim(), dir)
        }
    }
}

/** `setenv(3)` of this process, so the tray's `sd_bus_open_user` lands on a private bus. */
internal object ProcessEnv {
    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface LibC : Library {
        fun setenv(
            name: String,
            value: String,
            overwrite: Int,
        ): Int

        fun unsetenv(name: String): Int
    }

    private val libc: LibC by lazy { Native.load("c", LibC::class.java) }

    fun set(
        name: String,
        value: String,
    ) {
        check(libc.setenv(name, value, 1) == 0)
    }

    fun unset(name: String) {
        libc.unsetenv(name)
    }
}
