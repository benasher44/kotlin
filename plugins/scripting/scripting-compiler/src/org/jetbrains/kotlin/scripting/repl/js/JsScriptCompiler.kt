/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.repl.js

import org.jetbrains.kotlin.cli.common.repl.ReplCompileResult.*
import kotlin.script.experimental.api.*

class JsScriptCompiler(private val compiler: KJsReplCompiler) : ScriptCompiler {
    override suspend fun invoke(
        script: SourceCode,
        scriptCompilationConfiguration: ScriptCompilationConfiguration
    ): ResultWithDiagnostics<CompiledScript<*>> {
        val compileResult = compiler.compile(compiler.createState(), makeReplCodeLine(0, script.text))
        return when (compileResult) {
            is CompiledClasses -> ResultWithDiagnostics.Success(
                CompiledToJsScript(compileResult.data as String, scriptCompilationConfiguration)
            )
            is Incomplete -> ResultWithDiagnostics.Failure(
                ScriptDiagnostic("Incomplete code")
            )
            is Error -> ResultWithDiagnostics.Failure(
                ScriptDiagnostic(
                    message = compileResult.message,
                    severity = ScriptDiagnostic.Severity.ERROR
                )
            )
        }
    }
}
