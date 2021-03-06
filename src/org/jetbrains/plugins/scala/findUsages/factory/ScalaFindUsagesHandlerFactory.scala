package org.jetbrains.plugins.scala.findUsages.factory

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.scala.lang.psi.fake.FakePsiMethod
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScTypedDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import com.intellij.psi.{PsiMethod, PsiElement}
import com.intellij.find.findUsages.{FindUsagesHandlerFactory, JavaFindUsagesHandlerFactory, JavaFindUsagesHandler, FindUsagesHandler}
import org.jetbrains.plugins.scala.lang.psi.light._
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import scala.Array
import com.intellij.openapi.ui.Messages
import org.jetbrains.plugins.scala.ScalaBundle
import com.intellij.CommonBundle
import javax.swing.SwingUtilities

/**
 * User: Alexander Podkhalyuzin
 * Date: 17.08.2009
 */

class ScalaFindUsagesHandlerFactory(project: Project) extends FindUsagesHandlerFactory {
  override def canFindUsages(element: PsiElement): Boolean = {
    element match {
      case _: FakePsiMethod => true
      case _: ScTypedDefinition => true
      case _: ScTypeDefinition => true
      case _: PsiClassWrapper => true
      case _: ScFunctionWrapper => true
      case _: StaticPsiMethodWrapper => true
      case _: PsiTypedDefinitionWrapper => true
      case _: StaticPsiTypedDefinitionWrapper => true
      case _ => false
    }
  }

  override def createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler = {
    var replacedElement = element match {
      case wrapper: PsiClassWrapper => wrapper.definition
      case p: PsiTypedDefinitionWrapper => p.typedDefinition
      case p: StaticPsiTypedDefinitionWrapper => p.typedDefinition
      case f: ScFunctionWrapper => f.function
      case f: FakePsiMethod => f.navElement
      case s: StaticPsiMethodWrapper => s.method
      case _ => element
    }
    replacedElement match {
      case function: ScFunction if !forHighlightUsages =>
        val signs = function.superSignatures
        if (signs.length == 0 || signs.last.namedElement.isEmpty) Array(function)
        else {
          def showDialog() {
            val result = Messages.showYesNoCancelDialog(element.getProject,
              ScalaBundle.message("find.usages.method.has.supers", function.name), "Warning", Messages.getQuestionIcon)
            result match {
              case 0 =>
                val elem = signs.last.namedElement.get
                replacedElement = elem
              case 1 => //do nothing, it's ok
              case _ => replacedElement = null
            }
          }

          if (SwingUtilities.isEventDispatchThread) {
            showDialog()
          } else {
            SwingUtilities.invokeAndWait(new Runnable {
              def run() {
                showDialog()
              }
            })
          }
        }
      case _ =>
    }
    if (replacedElement == null) return FindUsagesHandler.NULL_HANDLER
    new ScalaFindUsagesHandler(replacedElement)
  }
}