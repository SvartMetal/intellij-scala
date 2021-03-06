package org.jetbrains.plugins.scala.codeInspection.methodSignature.quickfix

import com.intellij.openapi.project.Project
import com.intellij.codeInspection.ProblemDescriptor
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScMethodCall
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.codeInspection.AbstractFix

/**
 * Pavel Fatin
 */

class RemoveCallParentheses(call: ScMethodCall) extends AbstractFix("Remove call parentheses", call) {
  def doApplyFix(project: Project, descriptor: ProblemDescriptor) {
    val text = call.getInvokedExpr.getText
    val exp = ScalaPsiElementFactory.createExpressionFromText(text, call.getManager)
    call.replace(exp)
  }
}
