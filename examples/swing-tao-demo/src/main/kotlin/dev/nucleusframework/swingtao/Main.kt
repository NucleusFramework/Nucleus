package dev.nucleusframework.swingtao

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.window.tao.TaoApplication
import dev.nucleusframework.window.tao.TaoWindow
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/**
 * Pure-Swing sample driven by the Tao event loop — **no Compose anywhere**.
 *
 * [TaoApplication.run] seizes the calling (main) thread and pumps the native
 * Tao/winit event loop until [TaoApplication.exit]. The launch callback runs
 * on that Tao main thread; from it we hand the UI off to a plain Swing
 * [JFrame] on the AWT event-dispatch thread. Both loops then run concurrently
 * in the same process:
 *
 *  - the Tao loop owns the main thread and any native Tao windows,
 *  - the Swing EDT owns the [JFrame] and its widgets.
 *
 * The buttons cross from the EDT into the Tao loop — `TaoApplication`/`TaoWindow`
 * commands are thread-safe (they post user events to the loop), so opening and
 * closing a native Tao window from a Swing action just works.
 */
fun main() {
    try {
        TaoApplication.run { app ->
            // This callback fires once, on the Tao main thread.
            val taoThread = Thread.currentThread().name
            SwingUtilities.invokeLater { buildFrame(app, taoThread) }
        }
    } catch (t: Throwable) {
        // run() rethrows a fatal dispatch failure after logging it and showing
        // the native error dialog (#622). Without this catch the throwable
        // would skip exitProcess(0) below and the non-daemon Swing EDT would
        // keep the dead process alive.
        t.printStackTrace()
        exitProcess(1)
    }
    // run() returned: exit() was called and the Tao loop stopped. Once AWT is
    // up, its non-daemon event-dispatch thread keeps the JVM alive even after
    // main() returns — so force a clean process exit to actually quit.
    exitProcess(0)
}

private fun buildFrame(
    app: TaoApplication,
    taoThreadName: String,
) {
    // Held across threads: set from the EDT (open button) and cleared from the
    // Tao thread (native close callback), so an atomic reference is the simplest
    // safe hand-off.
    val taoWindow = AtomicReference<TaoWindow?>()

    val frame = JFrame("Swing on the Tao event loop")
    frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE

    val status = JLabel("No native Tao window open.")

    val header =
        JLabel("Swing UI + Tao event loop — same process, two toolkits").apply {
            font = font.deriveFont(Font.BOLD, 15f)
        }

    val info =
        JPanel(GridLayout(0, 1, 0, 2)).apply {
            border = BorderFactory.createEmptyBorder(8, 0, 8, 0)
            add(JLabel("Platform: ${Platform.Current}"))
            add(JLabel("Tao main thread: $taoThreadName"))
            add(JLabel("Swing EDT thread: ${Thread.currentThread().name}"))
        }

    // A ticking label proves the Swing EDT keeps running independently while
    // the Tao loop pumps on the main thread.
    val tick = JLabel("EDT alive: 0s")
    var seconds = 0
    Timer(1000) {
        seconds++
        tick.text = "EDT alive: ${seconds}s"
    }.apply { isRepeats = true }.start()

    val openButton =
        JButton("Open native Tao window").apply {
            addActionListener {
                if (taoWindow.get() != null) return@addActionListener
                // Bare window — no Compose renderer is attached, so it shows an
                // empty native surface. The point is that the Tao loop created
                // and owns a real OS window while Swing stays responsive.
                val window =
                    app.openWindow(
                        title = "Native Tao window (no renderer attached)",
                        width = 480.0,
                        height = 320.0,
                    )
                window.onCloseRequested {
                    // Fires on the Tao thread when the native X is clicked.
                    window.requestClose()
                    taoWindow.compareAndSet(window, null)
                    SwingUtilities.invokeLater { status.text = "No native Tao window open." }
                }
                taoWindow.set(window)
                status.text = "Native Tao window open (handle=${window.handle})."
            }
        }

    val closeButton =
        JButton("Close native Tao window").apply {
            addActionListener {
                taoWindow.getAndSet(null)?.requestClose()
                status.text = "No native Tao window open."
            }
        }

    val quitButton =
        JButton("Quit").apply {
            addActionListener { shutdown(app, frame, taoWindow) }
        }

    frame.addWindowListener(
        object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = shutdown(app, frame, taoWindow)
        },
    )

    val buttons =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(openButton)
            add(Box.createHorizontalStrut(8))
            add(closeButton)
            add(Box.createHorizontalGlue())
            add(quitButton)
        }

    val content =
        JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
            add(header, BorderLayout.NORTH)
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(info)
                    add(tick)
                    add(Box.createVerticalStrut(8))
                    add(status)
                },
                BorderLayout.CENTER,
            )
            add(buttons, BorderLayout.SOUTH)
        }

    frame.contentPane.add(content)
    frame.pack()
    frame.setLocationRelativeTo(null)
    frame.isVisible = true
}

/** Tears down the native Tao window (if any) and stops the Tao loop, which unblocks [main]. */
private fun shutdown(
    app: TaoApplication,
    frame: JFrame,
    taoWindow: AtomicReference<TaoWindow?>,
) {
    taoWindow.getAndSet(null)?.requestClose()
    frame.dispose()
    app.exit()
}
