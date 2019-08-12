/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.repl.js.test/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptingCompilerConfigurationComponentRegistrar
import org.jetbrains.kotlin.scripting.configuration.ScriptingConfigurationKeys
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.repl.js.JsReplEvaluator
import org.jetbrains.kotlin.scripting.repl.js.KJsReplCompiler
import org.jetbrains.kotlin.scripting.repl.js.ReplMessageCollector
import org.jetbrains.kotlin.scripting.repl.js.createCompileResult
import java.io.Closeable
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.jvm.JsDependency

class JsReplBase : Closeable {
    private val configuration = CompilerConfiguration()
    private val disposable = Disposer.newDisposable()
    val collector: ReplMessageCollector =
        ReplMessageCollector()
    val compiler: KJsReplCompiler
    val jsEngine: JsReplEvaluator

    private var snippetId: Int = 1 //index 0 for libs
    fun newSnippetId(): Int = snippetId++

    init {
        configuration.add(ComponentRegistrar.PLUGIN_COMPONENT_REGISTRARS, ScriptingCompilerConfigurationComponentRegistrar())
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, collector)
        configuration.put(CommonConfigurationKeys.MODULE_NAME, "repl.kts")
        val scriptConfiguration = ScriptCompilationConfiguration {
            baseClass("kotlin.Any")
            dependencies.append(JsDependency("compiler/ir/serialization.js/build/fullRuntime/klib"))
        }
        configuration.add(
            ScriptingConfigurationKeys.SCRIPT_DEFINITIONS,
            ScriptDefinition.FromConfigurations(ScriptingHostConfiguration(), scriptConfiguration, null)
        )

        compiler = KJsReplCompiler(configuration, disposable)

        jsEngine = JsReplEvaluator()
        jsEngine.eval(
            jsEngine.createState(),
            createCompileResult(compiler.stdlibCompiledResult)
        )
    }

    override fun close() {
        Disposer.dispose(disposable)
    }
}
