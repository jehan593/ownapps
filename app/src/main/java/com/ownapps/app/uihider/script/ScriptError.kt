package com.ownapps.app.uihider.script

/**
 * Thrown for any failure while lexing, parsing, or running a UIHider script.
 * [line] is the 1-based source line where the failure was detected (0 if unknown).
 *
 * Script execution is sandboxed: this is always caught at the blocker boundary so a
 * faulty script can never crash the accessibility service.
 */
class ScriptError(
    message: String,
    val line: Int = 0
) : Exception(if (line > 0) "Line $line: $message" else message)
