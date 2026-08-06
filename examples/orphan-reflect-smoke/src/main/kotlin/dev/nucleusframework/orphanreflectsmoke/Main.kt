package dev.nucleusframework.orphanreflectsmoke

import dev.nucleusframework.graalvm.GraalVmInitializer
import kotlin.system.exitProcess

/**
 * Room-shaped reflective load for #441.
 *
 * [AppDatabase_Impl] is never referenced by the constant pool — the FQCN is built by
 * concatenation (`base + "_Impl"`), the same pattern as
 * `androidx.room.util.KClassUtil.findAndInstantiateDatabaseImpl`. Without the orphan
 * project-class detector the native image drops `_Impl` and this process fails.
 *
 * There is intentionally **no** hand-written `reachability-metadata.json` entry for
 * `AppDatabase_Impl`.
 */
fun main() {
    GraalVmInitializer.initialize()

    val base = AppDatabase::class.java.name
    val implName = base + "_Impl"
    try {
        val clazz = Class.forName(implName)
        val instance = clazz.getDeclaredConstructor().newInstance() as AppDatabase
        val name = instance.name()
        check(name == "impl-ok") { "unexpected name: $name" }
        println(
            "OK orphan-reflect-smoke loaded $implName " +
                "native=${GraalVmInitializer.isNativeImage}",
        )
        exitProcess(0)
    } catch (t: Throwable) {
        System.err.println("FAIL orphan-reflect-smoke: ${t.javaClass.name}: ${t.message}")
        t.printStackTrace(System.err)
        exitProcess(1)
    }
}

/** Stand-in for a Room `@Database` abstract type — referenced by app code. */
abstract class AppDatabase {
    abstract fun name(): String
}

/**
 * Stand-in for Room-generated `*_Impl`. Must stay unreferenced from app bytecode
 * (no field types, no direct `AppDatabase_Impl::class` usage).
 */
class AppDatabase_Impl : AppDatabase() {
    override fun name(): String = "impl-ok"
}
