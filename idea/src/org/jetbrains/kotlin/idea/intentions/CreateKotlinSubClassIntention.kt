/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.intentions

import com.intellij.codeInsight.daemon.impl.quickfix.CreateClassKind
import com.intellij.codeInsight.intention.impl.CreateClassDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.overrideImplement.ImplementMembersHandler
import org.jetbrains.kotlin.idea.quickfix.unblockDocument
import org.jetbrains.kotlin.idea.refactoring.getOrCreateKotlinFile
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.KtPsiFactory.ClassHeaderBuilder
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.ModifiersChecker

const val IMPL_SUFFIX = "Impl"

class CreateKotlinSubClassIntention : SelfTargetingRangeIntention<KtClass>(KtClass::class.java, "Create Kotlin subclass") {

    override fun applicabilityRange(element: KtClass): TextRange? {
        val baseClass = element
        if (baseClass.getParentOfType<KtFunction>(true) != null) {
            // Local classes are not supported
            return null
        }
        if (!baseClass.isInterface() && !baseClass.isSealed() && !baseClass.isAbstract() && !baseClass.hasModifier(KtTokens.OPEN_KEYWORD)) {
            return null
        }
        val primaryConstructor = baseClass.getPrimaryConstructor()
        if (!baseClass.isInterface() && primaryConstructor != null) {
            val constructors = baseClass.getSecondaryConstructors() + primaryConstructor
            if (constructors.none() {
                !it.isPrivate() &&
                it.getValueParameters().all { it.hasDefaultValue() }
            }) {
                // At this moment we require non-private default constructor
                // TODO: handle non-private constructors with parameters
                return null
            }
        }
        text = getImplementTitle(baseClass)
        return TextRange(baseClass.startOffset, baseClass.getBody()?.lBrace?.startOffset ?: baseClass.endOffset)
    }

    private fun getImplementTitle(baseClass: KtClass) =
            when {
                baseClass.isInterface() -> "Implement interface"
                baseClass.isAbstract() -> "Implement abstract class"
                baseClass.isSealed() -> "Implement sealed class"
                else /* open class */ -> "Create subclass"
            }

    override fun applyTo(element: KtClass, editor: Editor?) {
        if (editor == null) throw IllegalArgumentException("This intention requires an editor")
        val baseClass = element
        if (baseClass.isSealed()) {
            createSealedSubclass(baseClass, editor)
        }
        else {
            createExternalSubclass(baseClass, editor)
        }
    }

    private fun defaultTargetName(klass: KtClass) = "${klass.name!!}$IMPL_SUFFIX"

    private fun createSealedSubclass(sealedClass: KtClass, editor: Editor) {
        val project = sealedClass.project
        val builder = buildClassHeader(defaultTargetName(sealedClass), sealedClass)
        val classFromText = KtPsiFactory(project).createClass(builder.asString())
        val body = sealedClass.getOrCreateBody()
        chooseAndImplementMethods(project, body.addBefore(classFromText, body.rBrace) as KtClass, editor)
    }

    private fun createExternalSubclass(baseClass: KtClass, editor: Editor) {
        var container: KtClassOrObject = baseClass
        var name = baseClass.name!!
        var visibility = ModifiersChecker.resolveVisibilityFromModifiers(baseClass, Visibilities.PUBLIC)
        while (!container.isPrivate() && !container.isProtected() && !(container is KtClass && container.isInner())) {
            val parent = container.containingClassOrObject
            if (parent != null) {
                val parentName = parent.name
                if (parentName != null) {
                    container = parent
                    name = "$parentName.$name"
                    val parentVisibility = ModifiersChecker.resolveVisibilityFromModifiers(parent, visibility)
                    if (Visibilities.compare(parentVisibility, visibility) ?: 0 < 0) {
                        visibility = parentVisibility
                    }
                }
            }
            if (container != parent) {
                break
            }
        }
        val project = baseClass.project
        val factory = KtPsiFactory(project)
        if (container.containingClassOrObject == null && !ApplicationManager.getApplication().isUnitTestMode) {
            val dlg = chooseSubclassToCreate(baseClass) ?: return
            val targetName = dlg.className
            val file = getOrCreateKotlinFile("$targetName.kt", dlg.targetDirectory)!!
            val builder = buildClassHeader(targetName, baseClass)
            file.add(factory.createClass(builder.asString()))
            chooseAndImplementMethods(project, file.getChildOfType<KtClass>()!!, editor)

        }
        else {
            val builder = buildClassHeader(defaultTargetName(baseClass), baseClass, name, visibility)
            val classFromText = factory.createClass(builder.asString())
            chooseAndImplementMethods(project, container.parent.addAfter(classFromText, container) as KtClass, editor)
        }
    }

    private fun chooseSubclassToCreate(baseClass: KtClass): CreateClassDialog? {
        val sourceDir = baseClass.containingFile.containingDirectory

        val aPackage = JavaDirectoryService.getInstance().getPackage(sourceDir)
        val dialog = object : CreateClassDialog(
                baseClass.project, text,
                defaultTargetName(baseClass),
                aPackage?.qualifiedName ?: "",
                CreateClassKind.CLASS, true,
                ModuleUtilCore.findModuleForPsiElement(baseClass)
        ) {
            override fun getBaseDir(packageName: String) = sourceDir

            override fun reportBaseInTestSelectionInSource() = true
        }
        return if (!dialog.showAndGet() || dialog.targetDirectory == null) null else dialog
    }

    private fun buildClassHeader(
            targetName: String,
            baseClass: KtClass,
            baseName: String = baseClass.name!!,
            defaultVisibility: Visibility = ModifiersChecker.resolveVisibilityFromModifiers(baseClass, Visibilities.PUBLIC)
    ): ClassHeaderBuilder {
        return ClassHeaderBuilder().apply {
            if (!baseClass.isInterface()) {
                if (defaultVisibility != Visibilities.PUBLIC) {
                    modifier(defaultVisibility.name)
                }
                if (baseClass.isInner()) {
                    modifier("inner")
                }
            }
            name(targetName)
            val typeParameters = baseClass.typeParameterList?.parameters
            typeParameters(typeParameters?.map { it.text } ?: emptyList())
            baseClass(baseName, typeParameters?.map { it.name!! } ?: emptyList(), baseClass.isInterface())
            typeConstraints(baseClass.typeConstraintList?.constraints?.map { it.text } ?: emptyList())
        }
    }

    private fun chooseAndImplementMethods(project: Project, targetClass: KtClass, editor: Editor) {
        editor.unblockDocument()
        editor.caretModel.moveToOffset(targetClass.textRange.startOffset)
        ImplementMembersHandler().invoke(project, editor, targetClass.containingFile)
    }
}