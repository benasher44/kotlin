/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.scratch.ui


import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.ui.ConfigurationModuleSelector
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CheckboxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.committed.LabeledComboBoxAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.caches.project.productionSourceInfo
import org.jetbrains.kotlin.idea.caches.project.testSourceInfo
import org.jetbrains.kotlin.idea.scratch.ScratchFile
import org.jetbrains.kotlin.idea.scratch.ScratchFileLanguageProvider
import org.jetbrains.kotlin.idea.scratch.actions.ClearScratchAction
import org.jetbrains.kotlin.idea.scratch.actions.RunScratchAction
import org.jetbrains.kotlin.idea.scratch.actions.StopScratchAction
import org.jetbrains.kotlin.idea.scratch.addScratchPanel
import org.jetbrains.kotlin.idea.scratch.output.ScratchOutputHandlerAdapter
import org.jetbrains.kotlin.idea.scratch.removeScratchPanel
import javax.swing.JComponent

interface ScratchTopPanel : Disposable {
    val scratchFile: ScratchFile
    val component: JComponent
    fun getModule(): Module?
    fun setModule(module: Module)
    fun hideModuleSelector()
    fun addModuleListener(f: (PsiFile, Module?) -> Unit)

    @TestOnly
    fun setReplMode(isSelected: Boolean)

    @TestOnly
    fun setMakeBeforeRun(isSelected: Boolean)

    @TestOnly
    fun setInteractiveMode(isSelected: Boolean)

    @TestOnly
    fun isModuleSelectorVisible(): Boolean

    fun changeMakeModuleCheckboxVisibility(isVisible: Boolean)
    fun updateToolbar()

    companion object {
        fun createPanel(
            project: Project,
            virtualFile: VirtualFile,
            editor: TextEditor,
            previewEditor: Editor
        ) {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return
            val scratchFile = ScratchFileLanguageProvider.get(psiFile.language)?.newScratchFile(project, editor, previewEditor) ?: return
            val panel = ActionsScratchTopPanel(scratchFile)

            val toolbarHandler = createUpdateToolbarHandler(panel)
            scratchFile.replScratchExecutor?.addOutputHandler(object : ScratchOutputHandlerAdapter() {
                override fun onFinish(file: ScratchFile) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!file.project.isDisposed) {
                            val scratch = file.getPsiFile()
                            if (scratch?.isValid == true) {
                                DaemonCodeAnalyzer.getInstance(project).restart(scratch)
                            }
                        }
                    }
                }
            })
            scratchFile.replScratchExecutor?.addOutputHandler(toolbarHandler)
            scratchFile.compilingScratchExecutor?.addOutputHandler(toolbarHandler)

            editor.addScratchPanel(panel)
        }

        private fun createUpdateToolbarHandler(panel: ScratchTopPanel) = object : ScratchOutputHandlerAdapter() {
            override fun onStart(file: ScratchFile) {
                panel.updateToolbar()
            }

            override fun onFinish(file: ScratchFile) {
                panel.updateToolbar()
            }
        }
    }
}

private class ActionsScratchTopPanel(override val scratchFile: ScratchFile) : ScratchTopPanel {
    override fun dispose() {
        scratchFile.replScratchExecutor?.stop()
        scratchFile.compilingScratchExecutor?.stop()
        scratchFile.editor.removeScratchPanel()
    }

    private val moduleChooserAction: ModulesComboBoxAction = ModulesComboBoxAction("Use classpath of module")

    private val isReplCheckbox: ListenableCheckboxAction = ListenableCheckboxAction("Use REPL")
    private val isMakeBeforeRunCheckbox: ListenableCheckboxAction = ListenableCheckboxAction("Make before run")
    private val isInteractiveCheckbox: ListenableCheckboxAction = ListenableCheckboxAction("Interactive mode")

    private val actionsToolbar: ActionToolbar

    init {
        moduleChooserAction.addOnChangeListener { updateToolbar() }

        isMakeBeforeRunCheckbox.addOnChangeListener {
            scratchFile.saveOptions {
                copy(isMakeBeforeRun = isMakeBeforeRunCheckbox.isSelected)
            }
        }

        isInteractiveCheckbox.addOnChangeListener {
            scratchFile.saveOptions {
                copy(isInteractiveMode = isInteractiveCheckbox.isSelected)
            }
        }

        isReplCheckbox.addOnChangeListener {
            scratchFile.saveOptions {
                copy(isRepl = isReplCheckbox.isSelected)
            }
            if (isReplCheckbox.isSelected) {
                // TODO start REPL process when checkbox is selected to speed up execution
                // Now it is switched off due to KT-18355: REPL process is keep alive if no command is executed
                //scratchFile.replScratchExecutor?.start()
            } else {
                scratchFile.replScratchExecutor?.stop()
            }
        }

        val toolbarGroup = DefaultActionGroup().apply {
            add(RunScratchAction())
            add(StopScratchAction())
            addSeparator()
            add(ClearScratchAction())
            addSeparator()
            add(moduleChooserAction)
            add(isMakeBeforeRunCheckbox)
            add(isInteractiveCheckbox)
            add(isReplCheckbox)
        }

        actionsToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.EDITOR_TOOLBAR, toolbarGroup, true)

        changeMakeModuleCheckboxVisibility(false)

        scratchFile.options.let {
            isReplCheckbox.isSelected = it.isRepl
            isMakeBeforeRunCheckbox.isSelected = it.isMakeBeforeRun
            isInteractiveCheckbox.isSelected = it.isInteractiveMode
        }
    }

    override val component: JComponent = actionsToolbar.component

    override fun getModule(): Module? = moduleChooserAction.selectedModule

    override fun setModule(module: Module) {
        moduleChooserAction.selectedModule = module
    }

    override fun hideModuleSelector() {
        moduleChooserAction.isVisible = false
    }

    override fun addModuleListener(f: (PsiFile, Module?) -> Unit) {
        moduleChooserAction.addOnChangeListener {
            val selectedModule = getModule()

            changeMakeModuleCheckboxVisibility(selectedModule != null)

            val psiFile = scratchFile.getPsiFile()
            if (psiFile != null) {
                f(psiFile, selectedModule)
            }
        }
    }

    @TestOnly
    override fun setReplMode(isSelected: Boolean) {
        isReplCheckbox.isSelected = isSelected
    }

    @TestOnly
    override fun setMakeBeforeRun(isSelected: Boolean) {
        isMakeBeforeRunCheckbox.isSelected = isSelected
    }

    @TestOnly
    override fun setInteractiveMode(isSelected: Boolean) {
        isInteractiveCheckbox.isSelected = isSelected
    }

    @TestOnly
    override fun isModuleSelectorVisible(): Boolean = moduleChooserAction.isVisible

    override fun changeMakeModuleCheckboxVisibility(isVisible: Boolean) {
        isMakeBeforeRunCheckbox.isVisible = isVisible
    }

    override fun updateToolbar() {
        ApplicationManager.getApplication().invokeLater {
            actionsToolbar.updateActionsImmediately()
        }
    }
}

interface ScratchPanelListener {
    fun panelAdded(panel: ScratchTopPanel)

    companion object {
        val TOPIC = Topic.create("ScratchPanelListener", ScratchPanelListener::class.java)
    }
}

private class ListenableCheckboxAction(val label: String) : CheckboxAction(label) {
    private val listeners: MutableList<() -> Unit> = mutableListOf()
    var isVisible: Boolean = true
    var isSelected: Boolean = false
        set(value) {
            field = value
            listeners.forEach { it() }
        }

    override fun isSelected(e: AnActionEvent): Boolean = isSelected

    override fun setSelected(e: AnActionEvent, newState: Boolean) {
        isSelected = newState
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isVisible = isVisible
    }

    fun addOnChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }
}

private class ModulesComboBoxAction(val label: String) : LabeledComboBoxAction(label) {
    private val listeners: MutableList<() -> Unit> = mutableListOf()

    var selectedModule: Module? = null
        set(value) {
            field = value
            listeners.forEach { it() }
        }

    var isVisible: Boolean = true

    override fun createPopupActionGroup(button: JComponent?): DefaultActionGroup =
        throw UnsupportedOperationException("Should not be called!")

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val project = dataContext.getData(CommonDataKeys.PROJECT)

        val actionGroup = DefaultActionGroup(ModuleIsNotSelectedAction(ConfigurationModuleSelector.NO_MODULE_TEXT))

        if (project != null) {
            val modules = ModuleManager.getInstance(project).modules.filter {
                it.productionSourceInfo() != null || it.testSourceInfo() != null
            }

            actionGroup.addAll(modules.map { SelectModuleAction(it) })
        }

        return actionGroup
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.apply {
            icon = selectedModule?.let { ModuleType.get(it).icon }
            text = selectedModule?.name ?: ConfigurationModuleSelector.NO_MODULE_TEXT
        }

        e.presentation.isVisible = isVisible
    }

    fun addOnChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private inner class ModuleIsNotSelectedAction(placeholder: String) : DumbAwareAction(placeholder) {
        override fun actionPerformed(e: AnActionEvent) {
            selectedModule = null
        }
    }

    private inner class SelectModuleAction(private val module: Module) : DumbAwareAction(module.name, null, ModuleType.get(module).icon) {
        override fun actionPerformed(e: AnActionEvent) {
            selectedModule = module
        }
    }
}
