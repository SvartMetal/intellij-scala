package org.jetbrains.plugins.scala
package lang
package parameterInfo
package patternParameterInfo

import _root_.scala.util.Sorting
import lexer.ScalaTokenTypes
import collection.mutable.ArrayBuffer
import com.intellij.psi.PsiElement
import com.intellij.codeInsight.hint.{HintUtil, ShowParameterInfoContext}
import java.awt.Color
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.openapi.fileEditor.{OpenFileDescriptor, FileEditorManager}
import com.intellij.openapi.vfs.{CharsetToolkit, LocalFileSystem}

import psi.api.ScalaFile
import java.io.File
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.io.FileUtil
import base.ScalaLightPlatformCodeInsightTestCaseAdapter

/**
 * @author Aleksander Podkhalyuzin
 * @since 25.04.2009
 */
abstract class PatternParameterInfoTestBase extends ScalaLightPlatformCodeInsightTestCaseAdapter {
  val caretMarker = "/*caret*/"

  protected def folderPath = baseRootPath() + "parameterInfo/patternParameterInfo/"

  protected def doTest() {
    import _root_.junit.framework.Assert._
    val filePath = folderPath + getTestName(false) + ".scala"
    val file = LocalFileSystem.getInstance.findFileByPath(filePath.replace(File.separatorChar, '/'))
    assert(file != null, "file " + filePath + " not found")
    val fileText = StringUtil.convertLineSeparators(FileUtil.loadFile(new File(file.getCanonicalPath), CharsetToolkit.UTF8))
    configureFromFileTextAdapter(getTestName(false) + ".scala", fileText)
    val scalaFile = getFileAdapter.asInstanceOf[ScalaFile]
    val offset = fileText.indexOf(caretMarker)
    assert(offset != -1, "Not specified caret marker in test case. Use /*caret*/ in scala file for this.")
    val fileEditorManager = FileEditorManager.getInstance(getProjectAdapter)
    val editor = fileEditorManager.openTextEditor(new OpenFileDescriptor(getProjectAdapter, file, offset), false)
    val context = new ShowParameterInfoContext(editor, getProjectAdapter, scalaFile, offset, -1)
    val handler = new ScalaPatternParameterInfoHandler
    val leafElement = scalaFile.findElementAt(offset)
    val element = PsiTreeUtil.getParentOfType(leafElement, handler.getArgumentListClass)
    handler.findElementForParameterInfo(context)
    val items = new ArrayBuffer[String]

    for (item <- context.getItemsToShow) {
      val uiContext = new ParameterInfoUIContext {
        def getDefaultParameterColor: Color = HintUtil.INFORMATION_COLOR
        def setupUIComponentPresentation(text: String, highlightStartOffset: Int, highlightEndOffset: Int,
                                        isDisabled: Boolean, strikeout: Boolean, isDisabledBeforeHighlight: Boolean,
                                        background: Color): String = {
          items.append(text)
          text
        }
        def isUIComponentEnabled: Boolean = false
        def getCurrentParameterIndex: Int = 0
        def getParameterOwner: PsiElement = element
        def setUIComponentEnabled(enabled: Boolean) {}
      }
      handler.updateUI(item, uiContext)
    }

    val itemsArray = items.toArray
    Sorting.quickSort[String](itemsArray)

    val res = new StringBuilder("")

    for (item <- itemsArray) res.append(item).append("\n")
    if (res.length > 0) res.replace(res.length - 1, res.length, "")
    val lastPsi = scalaFile.findElementAt(scalaFile.getText.length - 1)
    val text = lastPsi.getText
    val output = lastPsi.getNode.getElementType match {
      case ScalaTokenTypes.tLINE_COMMENT => text.substring(2).trim
      case ScalaTokenTypes.tBLOCK_COMMENT | ScalaTokenTypes.tDOC_COMMENT =>
        text.substring(2, text.length - 2).trim
      case _ => assertTrue("Test result must be in last comment statement.", false)
    }
    assertEquals(output, res.toString())
  }
}