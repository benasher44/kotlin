/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0collectTargetElements
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.intentions

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.intentions.ConvertToScopeIntention.ScopeFunction.*
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.getElementTextWithContext
import org.jetbrains.kotlin.psi.psiUtil.siblings
import org.jetbrains.kotlin.utils.KotlinExceptionWithAttachments

sealed class ConvertToScopeIntention(
    private val scopeFunction: ScopeFunction
) : SelfTargetingIntention<KtExpression>(KtExpression::class.java, "Convert to ${scopeFunction.functionName}") {

    enum class ScopeFunction(val functionName: String, val isParameterScope: Boolean) {
        ALSO(functionName = "also", isParameterScope = true),
        APPLY(functionName = "apply", isParameterScope = false),
        RUN(functionName = "run", isParameterScope = false),
        WITH(functionName = "with", isParameterScope = false);

        val receiver = if (isParameterScope) "it" else "this"
    }

    private data class RefactoringTargetAndItsValueExpression(
        val targetElement: PsiElement,
        val targetElementValue: PsiElement
    )

    private data class ScopedFunctionCallAndBlock(
        val scopeFunctionCall: KtExpression,
        val block: KtBlockExpression
    )

    override fun isApplicableTo(element: KtExpression, caretOffset: Int) = tryApplyTo(element, dryRun = true)

    override fun applyTo(element: KtExpression, editor: Editor?) {
        if (!tryApplyTo(element, dryRun = false)) {
            throw KotlinExceptionWithAttachments("Failed to apply ConvertToScope intention for \"${scopeFunction.functionName}\" option")
                .withAttachment("element", element.getElementTextWithContext())
        }
    }

    private fun KtExpression.tryGetExpressionToApply(referenceName: String): KtExpression? {
        val childOfBlock = PsiTreeUtil.findFirstParent(this) {
            it.parent is KtBlockExpression
        } as? KtExpression ?: return null

        return childOfBlock.takeIf { this is KtProperty || this.isTarget(referenceName) }
    }

    private fun tryApplyTo(element: KtExpression, dryRun: Boolean): Boolean {

        val invalidElementToRefactoring = when (element) {
            is KtProperty -> !element.isLocal
            is KtCallExpression -> false
            is KtDotQualifiedExpression -> false
            else -> true
        }
        if (invalidElementToRefactoring) return false

        val referenceName = element.tryExtractReferenceName() ?: return false
        val expressionToApply = element.tryGetExpressionToApply(referenceName) ?: return false
        val (firstTarget, lastTarget) = expressionToApply.collectTargetElementsRange(referenceName) ?: return false

        val refactoringTarget = tryGetFirstElementToRefactoring(expressionToApply, firstTarget) ?: return false

        if (dryRun) return true

        val psiFactory = KtPsiFactory(expressionToApply)
        val (scopeFunctionCall, block) =
            createScopeFunctionCall(psiFactory, refactoringTarget.targetElement) ?: return false

        block.addRange(refactoringTarget.targetElementValue, lastTarget)
        block.children.forEach { replace(it, referenceName, psiFactory) }

        refactoringTarget.targetElement.parent.addBefore(scopeFunctionCall, refactoringTarget.targetElement)
        refactoringTarget.targetElement.parent.deleteChildRange(refactoringTarget.targetElement, lastTarget)

        return true
    }

    private fun tryGetFirstElementToRefactoring(expressionToApply: KtExpression, firstTarget: PsiElement)
            : RefactoringTargetAndItsValueExpression? {

        val property by lazy(LazyThreadSafetyMode.NONE) { expressionToApply.prevProperty() }

        val propertyOrFirst = when (scopeFunction) {
            ALSO, APPLY -> property
            else -> firstTarget
        } ?: return null

        val isCorrectFirstOrProperty = when (scopeFunction) {
            ALSO, APPLY -> propertyOrFirst is KtProperty && propertyOrFirst.name !== null && propertyOrFirst.initializer !== null
            RUN -> propertyOrFirst is KtDotQualifiedExpression
            WITH -> propertyOrFirst is KtDotQualifiedExpression
        }

        if (!isCorrectFirstOrProperty) return null

        return RefactoringTargetAndItsValueExpression(propertyOrFirst, property?.nextSibling ?: firstTarget)
    }

    private fun replace(element: PsiElement, referenceName: String, psiFactory: KtPsiFactory) {
        when (element) {
            is KtDotQualifiedExpression -> {
                val replaced = element.deleteFirstReceiver()
                if (scopeFunction.isParameterScope) {
                    replaced.replace(psiFactory.createExpressionByPattern("${scopeFunction.receiver}.$0", replaced))
                }
            }
            is KtCallExpression -> {
                element.valueArguments.forEach { arg ->
                    if (arg.getArgumentExpression()?.text == referenceName) {
                        arg.replace(psiFactory.createArgument(scopeFunction.receiver))
                    }
                }
            }
            is KtBinaryExpression -> {
                listOfNotNull(element.left, element.right).forEach {
                    replace(it, referenceName, psiFactory)
                }
            }
        }
    }

    private fun KtExpression.tryExtractReferenceName(): String? {
        return when (scopeFunction) {
            ALSO, APPLY -> prevProperty()?.name
            RUN, WITH -> (this as? KtDotQualifiedExpression)?.let { getLeftMostReceiverExpression().text }
        }
    }

    private fun KtExpression.collectTargetElementsRange(referenceName: String): Pair<PsiElement, PsiElement>? {
        return when (scopeFunction) {
            ALSO, APPLY -> {
                val firstTarget = this as? KtProperty ?: this.prevProperty() ?: this
                val lastTarget = firstTarget.collectTargetLastElement(referenceName, forward = true)
                lastTarget ?: return null
                firstTarget to lastTarget
            }
            RUN, WITH -> {
                val firstTarget = collectTargetLastElement(referenceName, forward = false) ?: this
                val lastTarget = collectTargetLastElement(referenceName, forward = true)
                lastTarget ?: return null
                firstTarget to lastTarget
            }
        }
    }

    private fun KtExpression.collectTargetLastElement(referenceName: String, forward: Boolean): PsiElement? {
        return siblings(forward, withItself = false)
            .filter { it !is PsiWhiteSpace && it !is PsiComment && !(it is LeafPsiElement && it.elementType == KtTokens.SEMICOLON) }
            .takeWhile { it.isTarget(referenceName) }
            .lastOrNull()
    }

    private fun PsiElement.isTarget(referenceName: String): Boolean {
        when (this) {
            is KtDotQualifiedExpression -> {
                val callExpr = callExpression ?: return false
                if (callExpr.lambdaArguments.isNotEmpty() ||
                    callExpr.valueArguments.any { it.text == scopeFunction.receiver }
                ) return false

                val leftMostReceiver = getLeftMostReceiverExpression()
                if (leftMostReceiver.text != referenceName) return false

                if (leftMostReceiver.mainReference?.resolve() is PsiClass) return false
            }
            is KtCallExpression -> {
                val valueArguments = this.valueArguments
                if (valueArguments.none { it.getArgumentExpression()?.text == referenceName }) return false
                if (lambdaArguments.isNotEmpty() || valueArguments.any { it.text == scopeFunction.receiver }) return false
            }
            is KtBinaryExpression -> {
                val left = this.left ?: return false
                val right = this.right ?: return false
                if (left !is KtDotQualifiedExpression && left !is KtCallExpression
                    && right !is KtDotQualifiedExpression && right !is KtCallExpression
                ) return false
                if ((left is KtDotQualifiedExpression || left is KtCallExpression) && !left.isTarget(referenceName)) return false
                if ((right is KtDotQualifiedExpression || right is KtCallExpression) && !right.isTarget(referenceName)) return false
            }
            else -> return false
        }
        return !anyDescendantOfType<KtNameReferenceExpression> { it.text == scopeFunction.receiver }
    }

    private fun KtExpression.prevProperty(): KtProperty? {
        val blockChildExpression = PsiTreeUtil.findFirstParent(this) {
            it.parent is KtBlockExpression
        } ?: return null

        return blockChildExpression
            .siblings(forward = false, withItself = true)
            .firstOrNull { it is KtProperty && it.isLocal } as? KtProperty
    }

    private fun createScopeFunctionCall(factory: KtPsiFactory, element: PsiElement): ScopedFunctionCallAndBlock? {
        val scopeFunctionName = scopeFunction.functionName
        val (scopeFunctionCall, callExpression) = when (scopeFunction) {
            ALSO, APPLY -> {
                if (element !is KtProperty) return null
                val propertyName = element.name ?: return null
                val initializer = element.initializer ?: return null

                val initializerPattern = when (initializer) {
                    is KtDotQualifiedExpression -> initializer.text
                    is KtCallExpression -> initializer.text
                    is KtConstantExpression -> initializer.text
                    else -> "(${initializer.text})"
                }

                val property = factory.createProperty(
                    name = propertyName,
                    type = element.typeReference?.text,
                    isVar = element.isVar,
                    initializer = "$initializerPattern.$scopeFunctionName {}"
                )
                val callExpression = (property.initializer as? KtDotQualifiedExpression)?.callExpression ?: return null
                property to callExpression
            }
            RUN -> {
                if (element !is KtDotQualifiedExpression) return null
                val scopeFunctionCall = factory.createExpressionByPattern(
                    "$0.$scopeFunctionName {}",
                    element.getLeftMostReceiverExpression()
                ) as? KtQualifiedExpression ?: return null
                val callExpression = scopeFunctionCall.callExpression ?: return null
                scopeFunctionCall to callExpression
            }
            WITH -> {
                if (element !is KtDotQualifiedExpression) return null

                val scopeFunctionCall = factory.createExpressionByPattern(
                    "$scopeFunctionName($0) {}",
                    element.getLeftMostReceiverExpression()
                ) as? KtCallExpression ?: return null
                scopeFunctionCall to scopeFunctionCall
            }
        }

        val body = callExpression.lambdaArguments
            .firstOrNull()
            ?.getLambdaExpression()
            ?.bodyExpression
            ?: return null

        return ScopedFunctionCallAndBlock(scopeFunctionCall, body)
    }
}

class ConvertToAlsoIntention : ConvertToScopeIntention(ALSO)

class ConvertToApplyIntention : ConvertToScopeIntention(APPLY)

class ConvertToRunIntention : ConvertToScopeIntention(RUN)

class ConvertToWithIntention : ConvertToScopeIntention(WITH)
