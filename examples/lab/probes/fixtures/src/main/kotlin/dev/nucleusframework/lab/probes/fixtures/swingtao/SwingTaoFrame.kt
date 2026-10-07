package dev.nucleusframework.lab.probes.fixtures.swingtao

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

/** The Swing side of [SwingTaoFixture]; built and shown on the EDT. */
internal class SwingTaoFrame(
    private val app: TaoApplication,
    private val taoThreadName: String,
) {
    // Set from the EDT (open button), cleared from the Tao thread (native close callback).
    private val taoWindow = AtomicReference<TaoWindow?>()
    private val frame = JFrame("Swing on the Tao event loop")
    private val status = JLabel(NO_WINDOW)

    fun show() {
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) = shutdown()
            },
        )
        frame.contentPane.add(content())
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        println("swing-tao: frame shown (Tao thread $taoThreadName, EDT ${Thread.currentThread().name})")
    }

    private fun content(): JPanel {
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
        // A ticking label proves the EDT keeps running while the Tao loop pumps the main thread.
        val tick = JLabel("EDT alive: 0s")
        var seconds = 0
        Timer(1000) {
            seconds++
            tick.text = "EDT alive: ${seconds}s"
        }.apply { isRepeats = true }.start()

        val buttons =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(button("Open native Tao window", ::openTaoWindow))
                add(Box.createHorizontalStrut(8))
                add(button("Close native Tao window", ::closeTaoWindow))
                add(Box.createHorizontalGlue())
                add(button("Quit", ::shutdown))
            }
        return JPanel(BorderLayout(0, 8)).apply {
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
    }

    private fun button(
        text: String,
        action: () -> Unit,
    ) = JButton(text).apply { addActionListener { action() } }

    private fun openTaoWindow() {
        if (taoWindow.get() != null) return
        // Bare window: no Compose renderer attached, so an empty native surface. The point is
        // that the Tao loop owns a real OS window while Swing stays responsive.
        val window = app.openWindow(title = "Native Tao window (no renderer attached)", width = 480.0, height = 320.0)
        window.onCloseRequested {
            // Fires on the Tao thread when the native close button is clicked.
            window.requestClose()
            taoWindow.compareAndSet(window, null)
            SwingUtilities.invokeLater { status.text = NO_WINDOW }
            println("swing-tao: native window closed from its close button")
        }
        taoWindow.set(window)
        status.text = "Native Tao window open (handle=${window.handle})."
        println("swing-tao: native window opened, handle=${window.handle}")
    }

    private fun closeTaoWindow() {
        taoWindow.getAndSet(null)?.requestClose()
        status.text = NO_WINDOW
    }

    /** Closes the native window (if any) and stops the Tao loop, which unblocks `run`. */
    private fun shutdown() {
        closeTaoWindow()
        frame.dispose()
        app.exit()
    }

    private companion object {
        const val NO_WINDOW = "No native Tao window open."
    }
}
