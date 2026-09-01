package com.ownapps.app.uihider.script

/**
 * Host bridge between the interpreter and the Android world (accessibility nodes, overlays,
 * global actions). Implemented fresh per script run by the UIHider runtime; a stub
 * implementation is used for off-device testing.
 *
 * Global functions (`root`, `find`, `draw`, `back`, math, …) are resolved through
 * [callFunction]; pure helpers it doesn't recognise are expected to fall through to [Builtins].
 */
interface RuntimeApi {
    /** Predefined read-only globals available to the script, e.g. `app`, `screen`, `event`. */
    fun provideGlobals(): Map<String, Any?>

    /** Invoke a global function by [name]. Throws [ScriptError] if the name is unknown. */
    fun callFunction(name: String, args: List<Any?>, named: Map<String, Any?>): Any?
}

/**
 * A script-visible handle to a UI node. The host wraps an AccessibilityNodeInfo behind this
 * interface so the interpreter stays free of Android types.
 */
interface ScriptNode {
    /** Read a property such as `x`, `text`, `clickable`, `childCount`. */
    fun prop(name: String): Any?

    /** Call a method such as `find`, `findAll`, `child`, `parent`, `hide`. */
    fun call(name: String, args: List<Any?>, named: Map<String, Any?>): Any?
}
