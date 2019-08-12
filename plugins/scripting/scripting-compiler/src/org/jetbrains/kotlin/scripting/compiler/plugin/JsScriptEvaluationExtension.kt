/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.compiler.plugin

import com.intellij.core.JavaCoreProjectEnvironment
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.K2JSCompilerArguments
import org.jetbrains.kotlin.cli.common.extensions.ScriptEvaluationExtension
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionProvider
import java.io.File
import kotlin.script.experimental.host.toScriptSource
import org.jetbrains.kotlin.scripting.repl.js.KJsReplCompiler
import org.jetbrains.kotlin.scripting.repl.js.CompiledToJsScript
import org.jetbrains.kotlin.scripting.repl.js.JsScriptCompiler
import org.jetbrains.kotlin.scripting.repl.js.JsScriptEvaluator
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.scripting.configuration.ScriptingConfigurationKeys
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.platform
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.JsDependency

class JsScriptEvaluationExtension : ScriptEvaluationExtension {
    override fun eval(
        arguments: CommonCompilerArguments,
        configuration: CompilerConfiguration,
        projectEnvironment: JavaCoreProjectEnvironment
    ): ExitCode {
        val scriptConfiguration = ScriptCompilationConfiguration {
            baseClass("kotlin.Any")
            dependencies.append(JsDependency("compiler/ir/serialization.js/build/fullRuntime/klib"))
            platform.put("JS")
        }
        configuration.add(
            ScriptingConfigurationKeys.SCRIPT_DEFINITIONS,
            ScriptDefinition.FromConfigurations(ScriptingHostConfiguration(), scriptConfiguration, null)
        )

        val disposable = Disposer.newDisposable()

        val replCompiler = KJsReplCompiler(configuration, disposable)
        val compiler = JsScriptCompiler(replCompiler)
        val evaluator = JsScriptEvaluator()

        val sourcePath = (arguments as K2JSCompilerArguments).scriptPath!!
        val scriptFile = File(sourcePath)
        val sourceCode = scriptFile.toScriptSource()

        val messageCollector = configuration.getNotNull(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY)
        val scriptDefinitionProvider = ScriptDefinitionProvider.getInstance(projectEnvironment.project)
        if (scriptDefinitionProvider == null) {
            messageCollector.report(CompilerMessageSeverity.ERROR, "Unable to process the script, scripting plugin is not configured")
            return ExitCode.COMPILATION_ERROR
        }
        val definition = scriptDefinitionProvider.findDefinition(scriptFile) ?: scriptDefinitionProvider.getDefaultDefinition()
        val scriptArgs =
            if (arguments.freeArgs.isNotEmpty()) arguments.freeArgs.subList(1, arguments.freeArgs.size) else emptyList<String>()
        val evaluationConfiguration = definition.evaluationConfiguration.with {
            constructorArgs(scriptArgs.toTypedArray())
        }
        val scriptCompilationConfiguration = definition.compilationConfiguration

        return runBlocking {
            val compiledScript = compiler.invoke(sourceCode, scriptCompilationConfiguration).valueOr {
                return@runBlocking ExitCode.COMPILATION_ERROR
            }

            evaluator.invoke(
                CompiledToJsScript(
                    replCompiler.stdlibCompiledResult,
                    scriptCompilationConfiguration
                ),
                evaluationConfiguration
            )
            val evalResult = evaluator.invoke(compiledScript, evaluationConfiguration).valueOr {
                return@runBlocking ExitCode.INTERNAL_ERROR
            }

            when (evalResult.returnValue) {
                is ResultValue.Value -> {
                    println((evalResult.returnValue as ResultValue.Value).value)
                    ExitCode.OK
                }
                is ResultValue.Error -> {
                    System.err.println(evalResult.returnValue as ResultValue.Error)
                    ExitCode.SCRIPT_EXECUTION_ERROR
                }
                else -> ExitCode.OK
            }
        }.also {
            Disposer.dispose(disposable)
        }
    }

    override fun isAccepted(arguments: CommonCompilerArguments): Boolean {
        return arguments is K2JSCompilerArguments
    }
}
