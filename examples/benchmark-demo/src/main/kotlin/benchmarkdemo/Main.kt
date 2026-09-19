package benchmarkdemo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.NucleusDecoratedWindowTheme
import dev.nucleusframework.window.TitleBar
import dev.nucleusframework.window.macOSLargeCornerRadius
import dev.nucleusframework.window.styling.TitleBarColors
import dev.nucleusframework.window.styling.TitleBarMetrics
import dev.nucleusframework.window.styling.TitleBarStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main(args: Array<String>) {
    if (args.contains("--headless")) {
        printSuite()
        return
    }
    nucleusApplication(args = args) {
        val titleBarStyle =
            TitleBarStyle(
                colors =
                    TitleBarColors(
                        background = Color(0xFFECECEE),
                        inactiveBackground = Color(0xFFF5F5F7),
                        content = Color(0xFF1A1A1A),
                        border = Color.Transparent,
                    ),
                metrics = TitleBarMetrics(height = 36.dp),
            )
        NucleusDecoratedWindowTheme(isDark = false, titleBarStyle = titleBarStyle) {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                state = rememberWindowState(size = DpSize(980.dp, 860.dp)),
                title = "Nucleus Benchmark — JIT vs GraalVM -O3",
                minimumSize = DpSize(760.dp, 600.dp),
            ) {
                TitleBar(modifier = Modifier.macOSLargeCornerRadius()) {
                    Row(modifier = Modifier.align(Alignment.Start).padding(start = 12.dp)) {
                        Text(
                            "Nucleus Benchmark",
                            color = Color(0xFF55565B),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                MaterialTheme(colorScheme = lightColorScheme()) {
                    App()
                }
            }
        }
    }
}

/** Headless run — CPU suite only, with self-checks, prints the table and writes the JSON. */
private fun printSuite() {
    println("Runtime: ${runtimeLabel()} — $CORES cores")

    // Self-checks: known prime count + hand-rolled SHA-256 vs the JDK reference.
    val sieveBuf = ByteArray(Sieve.LIMIT + 1)
    val primes = Sieve.count(sieveBuf)
    check(primes == 1_270_607.0) { "sieve self-check failed: got $primes primes" }
    val shaBuf = ByteArray(Sha256.BYTES)
    Sha256.fill(shaBuf)
    val ref =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(shaBuf)
    var refSum = 0.0
    for (i in 0 until 8) {
        var v = 0L
        for (j in 0 until 4) v = (v shl 8) or (ref[i * 4 + j].toLong() and 0xFF)
        refSum += v.toDouble()
    }
    val mine = Sha256.digest(shaBuf)
    check(mine == refSum) { "sha256 self-check failed: $mine != $refSum" }
    println("Self-checks OK — pi(2e7)=1270607, sha256==MessageDigest (digestSum=$mine)")

    println("Warming up and measuring (best of $MEASURE_RUNS)...")
    val cpu =
        runCpuSuite { r ->
            println(
                "  %-14s x%-2d %10.2f %s  (best %.3fs)"
                    .format(r.name, r.threads, r.throughputM, r.unit, r.bestSeconds),
            )
        }
    val composite = compositeScore(cpu)
    println("Composite CPU score (geo-mean): %.2f".format(composite))
    check(cpu.all { it.throughputM.isFinite() && it.throughputM > 0 }) {
        "kernel produced non-positive throughput — measurement is broken"
    }
    val path = writeResultsJson(cpu, composite, maxParticles = null, listLoadMs = null)
    check(cpu.any { it.name == "pi" }) { "pi bench missing from suite" }
    println("Results JSON: $path")
}

@Composable
private fun App() {
    val cpuResults = remember { mutableStateListOf<BenchResult>() }
    var phase by remember { mutableStateOf("cpu") }
    var composite by remember { mutableStateOf<Double?>(null) }
    var maxParticles by remember { mutableStateOf<Int?>(null) }
    var maxStars by remember { mutableStateOf<Int?>(null) }
    var maxTexts by remember { mutableStateOf<Int?>(null) }
    var rampStatus by remember { mutableStateOf("") }
    var listMs by remember { mutableStateOf<Double?>(null) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var runId by remember { mutableStateOf(0) }
    val particles = remember { Particles(RAMP_MAX) }
    val stars = remember { Stars(STAR_MAX) }
    val texts = remember { Texts(TEXT_MAX) }
    var frameTick by remember { mutableStateOf(0L) }
    var listItems by remember { mutableStateOf(listOf<String>()) }

    // The whole suite runs automatically on launch (and again on "Run again").
    LaunchedEffect(runId) {
        cpuResults.clear()
        composite = null
        maxParticles = null
        maxStars = null
        maxTexts = null
        rampStatus = ""
        listMs = null
        savedPath = null

        phase = "cpu"
        val cpu = withContext(Dispatchers.Default) { runCpuSuite { cpuResults.add(it) } }
        composite = compositeScore(cpu)

        fun window(unitName: String): (Int, Double) -> Unit =
            { count, fps -> rampStatus = "%,d $unitName — %.1f fps".format(count, fps) }

        phase = "particles"
        particles.reset()
        maxParticles =
            runRenderRamp(
                RAMP_STEP,
                RAMP_MAX,
                getActive = { particles.active },
                setActive = { particles.active = it },
                advance = { particles.step(it) },
                onTick = { frameTick = it },
                onWindow = window("particles"),
            )

        phase = "stars"
        stars.reset()
        maxStars =
            runRenderRamp(
                STAR_STEP,
                STAR_MAX,
                getActive = { stars.active },
                setActive = { stars.active = it },
                advance = { stars.t += it },
                onTick = { frameTick = it },
                onWindow = window("stars"),
            )

        phase = "texts"
        texts.reset()
        maxTexts =
            runRenderRamp(
                TEXT_STEP,
                TEXT_MAX,
                getActive = { texts.active },
                setActive = { texts.active = it },
                advance = { texts.t += it },
                onTick = { frameTick = it },
                onWindow = window("texts"),
            )

        phase = "list"
        listMs = runListLoadBench { listItems = it }

        phase = "save"
        savedPath =
            withContext(Dispatchers.IO) {
                writeResultsJson(cpu, compositeScore(cpu), maxParticles, maxStars, maxTexts, listMs)
            }
        phase = "done"
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("Nucleus Benchmark Suite", style = MaterialTheme.typography.headlineMedium)
        Text("${runtimeLabel()} — $CORES cores", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            when (phase) {
                "cpu" -> "Running CPU benchmarks… (${cpuResults.size}/13)"
                "particles", "stars", "texts" ->
                    "Render ramp ($phase) — pushing until fps drops below ${RAMP_MIN_FPS.toInt()}…  $rampStatus"
                "list" -> "List-load benchmark — $LIST_ROWS rows"
                "save" -> "Saving results…"
                else -> "Done — JSON: $savedPath"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))

        val canvasModifier = Modifier.width(600.dp).height(400.dp).background(Color(0xFFEEF0F3))
        if (phase == "particles") {
            // Flat coords + native Skia drawPoints: a List<Offset> here would box 500k Offsets
            // per frame and the ramp would measure the GC instead of the renderer. The buffer is
            // reallocated only when the ramp grows (≤ ~30 times), never per frame.
            val coordsHolder = remember { intArrayOf(0) to arrayOf(FloatArray(0)) }
            val skiaPaint =
                remember {
                    org.jetbrains.skia.Paint().apply {
                        color = 0xFF2563EB.toInt()
                        strokeWidth = 3f
                    }
                }
            Canvas(modifier = canvasModifier) {
                frameTick // state read → invalidates this draw every frame
                val n = particles.active
                if (coordsHolder.second[0].size != n * 2) coordsHolder.second[0] = FloatArray(n * 2)
                val coords = coordsHolder.second[0]
                for (i in 0 until n) {
                    coords[i * 2] = particles.x[i] * size.width
                    coords[i * 2 + 1] = particles.y[i] * size.height
                }
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawPoints(coords, skiaPaint)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (phase == "stars") {
            Canvas(modifier = canvasModifier) {
                frameTick
                val star = Path()
                val color = Color(0xFF2563EB)
                for (i in 0 until stars.active) {
                    val cx = stars.x[i] * size.width
                    val cy = stars.y[i] * size.height
                    val theta = stars.omega[i] * stars.t
                    star.reset()
                    for (k in 0 until 12) {
                        val r = if (k % 2 == 0) 14f else 6f
                        val a = theta + k * (Math.PI / 6)
                        val vx = cx + r * Math.cos(a).toFloat()
                        val vy = cy + r * Math.sin(a).toFloat()
                        if (k == 0) star.moveTo(vx, vy) else star.lineTo(vx, vy)
                    }
                    star.close()
                    drawPath(star, color, alpha = 0.5f)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (phase == "texts") {
            val measurer = rememberTextMeasurer()
            val layouts =
                remember(measurer) {
                    List(100) { measurer.measure(AnnotatedString("Bench#$it"), TextStyle(fontSize = 12.sp)) }
                }
            Canvas(modifier = canvasModifier) {
                frameTick
                for (i in 0 until texts.active) {
                    val ty = (texts.y0[i] + 0.03f * texts.t.toFloat()) % 1f
                    drawText(
                        layouts[i % 100],
                        color = Color(0xFF334155),
                        topLeft = Offset(texts.x[i] * size.width, ty * size.height),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (phase == "list") {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                items(listItems) { Text(it) }
            }
            Spacer(Modifier.height(12.dp))
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp)) {
                cpuResults.forEach { r ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(r.name, modifier = Modifier.width(150.dp))
                        Text("×${r.threads}", modifier = Modifier.width(50.dp))
                        Text("%.2f %s".format(r.throughputM, r.unit), modifier = Modifier.width(180.dp))
                        Text("best %.3fs".format(r.bestSeconds))
                    }
                }
                composite?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Composite CPU score: %.1f".format(it), style = MaterialTheme.typography.titleLarge)
                }
                maxParticles?.let { Text("Particles: %,d sustained @ ≥%.0f fps".format(it, RAMP_MIN_FPS)) }
                maxStars?.let { Text("Stars: %,d sustained @ ≥%.0f fps".format(it, RAMP_MIN_FPS)) }
                maxTexts?.let { Text("Texts: %,d sustained @ ≥%.0f fps".format(it, RAMP_MIN_FPS)) }
                if (maxParticles != null && maxStars != null && maxTexts != null) {
                    Text(
                        "Graphics score: %,.0f".format(graphicsScore(maxParticles!!, maxStars!!, maxTexts!!)),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                listMs?.let { Text("List load: %.1f ms ($LIST_ROWS rows, best of 3)".format(it)) }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(enabled = phase == "done", onClick = { runId++ }) {
            Text(if (phase == "done") "Run again" else "Running…")
        }
    }
}

/**
 * Generic render ramp: 1s windows; while a window averages ≥ [RAMP_MIN_FPS], grow the active
 * count by [step] (up to [max]). Returns the largest sustained count — the machine's ceiling.
 */
private suspend fun runRenderRamp(
    step: Int,
    max: Int,
    getActive: () -> Int,
    setActive: (Int) -> Unit,
    advance: (Float) -> Unit,
    onTick: (Long) -> Unit,
    onWindow: (Int, Double) -> Unit,
): Int {
    var sustained = 0
    var warmup = true // first window absorbs startup jank (canvas alloc, shader warmup) — never judged
    var failedOnce = false // a failure must be confirmed by a second consecutive failing window
    while (true) {
        var frames = 0
        var start = 0L
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (start == 0L) {
                    start = now
                } else {
                    advance(minOf((now - last) / 1e9, 1.0 / 30.0).toFloat())
                }
                last = now
                frames++
                onTick(now)
            }
            if (last - start >= (RAMP_WINDOW_S * 1e9).toLong()) break
        }
        if (warmup) {
            warmup = false
            continue
        }
        val fps = (frames - 1).toDouble() / ((last - start) / 1e9)
        onWindow(getActive(), fps)
        if (fps >= RAMP_MIN_FPS) {
            failedOnce = false
            sustained = getActive()
            if (getActive() >= max) break
            // Geometric growth (+25%/window, min = step): reaches any ceiling in ~20 windows
            // while keeping fine granularity near the breaking point.
            setActive(minOf(max, getActive() + maxOf(step, getActive() / 4)))
        } else if (!failedOnce) {
            failedOnce = true // transient hitch? re-run the same count once
        } else {
            break
        }
    }
    return sustained
}

/** List-load bench: time from setting [LIST_ROWS] fresh items until the next rendered frame. */
private suspend fun runListLoadBench(setItems: (List<String>) -> Unit): Double {
    var best = Double.MAX_VALUE
    repeat(3) {
        setItems(emptyList())
        awaitFrame()
        awaitFrame()
        val t0 = System.nanoTime()
        setItems(List(LIST_ROWS) { i -> "Item $i — payload ${(i.toLong() * 2654435761L) and 0xFFFFFFFFL}" })
        awaitFrame()
        best = minOf(best, (System.nanoTime() - t0) / 1e6)
    }
    setItems(emptyList())
    return best
}

private suspend fun awaitFrame() {
    withFrameNanos { }
}
