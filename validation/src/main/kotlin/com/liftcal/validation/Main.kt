package com.liftcal.validation

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
LiftCal validation harness

  report   --fixtures DIR [--out FILE]   Evaluate all *.json fixtures in DIR and write a markdown report
  generate --out DIR [--subjects N] [--sessions M] [--seed S]
                                         Write synthetic fixtures (for pipeline testing only)
"""

fun main(args: Array<String>) {
    val cmd = args.firstOrNull()
    val opts = args.drop(1).chunked(2).filter { it.size == 2 && it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it[1] }
    when (cmd) {
        "report" -> {
            val dir = File(opts["fixtures"] ?: fail("--fixtures is required"))
            val loaded = Fixtures.loadDir(dir)
            if (loaded.isEmpty()) fail("no fixtures in $dir")
            val md = Report.markdown(Harness.evaluate(loaded))
            val out = opts["out"]
            if (out != null) {
                File(out).apply { parentFile?.mkdirs() }.writeText(md)
                println("Wrote $out (${loaded.size} sessions)")
            } else {
                println(md)
            }
        }
        "generate" -> {
            val dir = File(opts["out"] ?: fail("--out is required"))
            val g = SyntheticGenerator(opts["seed"]?.toInt() ?: 2026)
                .writeTo(dir, opts["subjects"]?.toInt() ?: 4, opts["sessions"]?.toInt() ?: 3)
            println("Wrote ${g.size} synthetic fixtures to $dir")
        }
        else -> {
            println(USAGE.trimIndent())
            if (cmd != null && cmd != "help") exitProcess(2)
        }
    }
}

private fun fail(msg: String): Nothing {
    System.err.println(msg)
    exitProcess(2)
}
