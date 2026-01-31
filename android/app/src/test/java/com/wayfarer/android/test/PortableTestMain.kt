package com.wayfarer.android.test

import org.junit.Test
import org.junit.runner.JUnitCore
import java.io.File
import java.lang.reflect.Modifier

/**
 * Portable JUnit4 test launcher for Windows + non-ASCII paths.
 *
 * Android Gradle Plugin can have trouble constructing a readable test classpath
 * on some Windows setups when the project path contains non-ASCII characters.
 * This runner discovers and executes JUnit4 tests from compiled test class
 * directories passed as args.
 */
object PortableTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val roots = args
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it) }
            .filter { it.isDirectory }

        val classNames = mutableListOf<String>()
        for (root in roots) {
            collectClassNames(root = root, dir = root, out = classNames)
        }

        val testClasses = classNames
            .mapNotNull { className ->
                try {
                    Class.forName(className)
                } catch (_: Throwable) {
                    null
                }
            }
            .filter { clazz -> isJUnit4TestClass(clazz) }
            .sortedBy { it.name }

        if (testClasses.isEmpty()) {
            println("PortableTestMain: no JUnit4 tests discovered.")
            return
        }

        println("PortableTestMain: running ${testClasses.size} test class(es)")
        val result = JUnitCore.runClasses(*testClasses.toTypedArray())

        if (!result.wasSuccessful()) {
            System.err.println("PortableTestMain: failures=${result.failureCount}")
            for (failure in result.failures) {
                System.err.println("- ${failure}")
                failure.exception?.printStackTrace(System.err)
            }
            kotlin.system.exitProcess(1)
        }
    }

    private fun collectClassNames(root: File, dir: File, out: MutableList<String>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                collectClassNames(root, child, out)
                continue
            }

            val name = child.name
            if (!name.endsWith(".class")) continue
            if (name == "module-info.class") continue
            if (name.contains('$')) continue

            val relPath = root.toURI().relativize(child.toURI()).path
            if (!relPath.endsWith(".class")) continue

            val className = relPath
                .removeSuffix(".class")
                .replace('/', '.')
                .replace('\\', '.')
            out.add(className)
        }
    }

    private fun isJUnit4TestClass(clazz: Class<*>): Boolean {
        val mods = clazz.modifiers
        if (clazz.isInterface || Modifier.isAbstract(mods)) return false
        if (clazz.isAnnotation || clazz.isEnum) return false

        return clazz.declaredMethods.any { m -> m.getAnnotation(Test::class.java) != null }
    }
}
