package com.liftcal.validation

import java.util.Locale
import kotlin.math.abs

object Report {
    private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)

    fun markdown(e: Evaluation): String = buildString {
        appendLine("# LiftCal validation report")
        appendLine()
        val synthetic = e.sessions.count { it.synthetic }
        if (synthetic == e.sessions.size) {
            appendLine("> **All ${e.sessions.size} sessions are synthetic.** They exercise the pipeline and show the")
            appendLine("> mechanisms at work, but say nothing about real-world accuracy. Only sessions measured")
            appendLine("> with indirect calorimetry can support a claim of beating other wearables.")
        } else if (synthetic > 0) {
            appendLine("> $synthetic of ${e.sessions.size} sessions are synthetic; see the per-session table.")
        }
        appendLine()
        appendLine("Sessions: ${e.sessions.size}, subjects: ${e.sessions.map { it.subject }.distinct().size}. ")
        appendLine("Reference kinds: ${e.sessions.groupingBy { it.referenceKind }.eachCount().entries.joinToString { "${it.key} (${it.value})" }}.")
        appendLine()
        appendLine("## Session-level agreement")
        appendLine()
        appendLine("| Method | n | MAPE % | Bias % | Mean diff kcal | 95% LoA kcal | CCC |")
        appendLine("|---|---:|---:|---:|---:|---:|---:|")
        for (m in e.methods.sortedBy { e.stats(it)?.mape ?: Double.MAX_VALUE }) {
            val s = e.stats(m) ?: continue
            appendLine("| $m | ${s.n} | ${f1(s.mape)} | ${f1(s.biasPct)} | ${f0(s.meanDiff)} | ${f0(s.loaLow)} to ${f0(s.loaHigh)} | ${f2(s.ccc)} |")
        }
        appendLine()
        appendLine("LiftCal 90% interval coverage: ${f0(e.intervalCoverage * 100)}% of sessions (target about 90%).")
        val win = e.sessions.flatMap { it.windowErrorsPct }
        if (win.isNotEmpty()) {
            appendLine()
            appendLine("LiftCal (priors) 5-minute window MAPE: ${f1(win.map { abs(it) }.average())}% over ${win.size} windows.")
        }
        appendLine()
        appendLine("## Per session")
        appendLine()
        val methods = e.methods
        appendLine("| Session | Subject | HR quality | Reference kcal | " + methods.joinToString(" | ") + " |")
        appendLine("|---|---|---:|---:|" + methods.joinToString("") { "---:|" })
        for (s in e.sessions) {
            val cells = methods.joinToString(" | ") { m ->
                s.estimates[m]?.let { "${f0(it)} (${if (it >= s.referenceKcal) "+" else ""}${f0((it - s.referenceKcal) / s.referenceKcal * 100)}%)" } ?: "-"
            }
            appendLine("| ${s.id}${if (s.synthetic) " (synthetic)" else ""} | ${s.subject} | ${f0(s.hrQuality * 100)}% | ${f0(s.referenceKcal)} | $cells |")
        }
    }
}
