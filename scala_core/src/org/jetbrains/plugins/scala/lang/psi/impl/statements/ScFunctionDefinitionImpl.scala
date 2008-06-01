package org.jetbrains.plugins.scala.lang.psi.impl.statements

import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.ScalaElementTypes
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElementImpl
import com.intellij.psi.tree.TokenSet
import com.intellij.lang.ASTNode
import com.intellij.psi.tree.IElementType;
import com.intellij.psi._
import org.jetbrains.annotations._
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.icons.Icons
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params._
import org.jetbrains.plugins.scala.lang.psi.api.base.types._
import _root_.scala.collection.mutable._
import org.jetbrains.plugins.scala.lang.psi.api.toplevel._
import typedef._
import packaging.ScPackaging
import com.intellij.psi.scope._

/** 
* @author Alexander Podkhalyuzin
* Date: 22.02.2008
*/

class ScFunctionDefinitionImpl(node: ASTNode) extends ScFunctionImpl (node) with ScFunctionDefinition {

  override def processDeclarations(processor: PsiScopeProcessor,
                                  state: ResolveState,
                                  lastParent: PsiElement,
                                  place: PsiElement): Boolean = {
    import org.jetbrains.plugins.scala.lang.resolve._
    //the following not compiling seems a bug in scalac
    //if(!super[ScTypeParametersOwner].processDeclarations(processor, state, lastParent, place)) return false

    body match {
      case Some(x) if x == lastParent =>
        for (p <- parameters) {
          if (!processor.execute(p, state)) return false
        }
        true
      case _ => true
    }
  }

  override def toString: String = "ScFunctionDefinition"

  def getFunctionsAndTypeDefs: Seq[ScalaPsiElement] =
    body match {
      case None => Seq.empty
      case Some(elem) => for (child <- elem.getChildren() if (child.isInstanceOf[ScTypeDefinition] || child.isInstanceOf[ScFunction]))
              yield child.asInstanceOf[ScalaPsiElement]
    }

  override def getTextOffset(): Int = getId.getTextRange.getStartOffset

  def getTypeDefinitions(): Seq[ScTypeDefinition] = {
    body match {
      case None => return Seq.empty
      case Some(body) => body.getChildren.flatMap(collectTypeDefs(_))
    }
  }

  override def collectTypeDefs(child: PsiElement) = child match {
    case f: ScFunctionDefinition => f.getTypeDefinitions
    case t: ScTypeDefinition => List(t) ++ t.getTypeDefinitions
    case _ => Seq.empty
  }

  /**
  * Fake method to provide type-unsafe Scala Run Configuration
  */
  override def isMainMethod: Boolean = {
    val obj = getContainingClass
    if (!getName.equals("main") || !obj.isInstanceOf[ScObject]) return false
    obj.getParent match {
      case _: PsiFile | _: ScPackaging => {}
      case _ => return false
    }
    val pc = paramClauses
    if (pc == null) return false
    val params = pc.params
    if (params.length != 1) return false
    params(0).typeElement match {
      case Some(g: ScParametrizedTypeElement) => {
        if (!"Array".equals(g.getSimpleTypeElement.getText)) return false
        val args = g.getTypeArgs.typeArgs
        if (args.length != 1) return false
        args(0).getText == "String"
      }
      case None => return false
    }
  }



}