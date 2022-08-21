/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.lsp.xml.providers

import com.android.aaptcompiler.AaptResourceType.STYLEABLE
import com.android.aaptcompiler.ConfigDescription
import com.android.aaptcompiler.Reference
import com.android.aaptcompiler.ResourceGroup
import com.android.aaptcompiler.Styleable
import com.itsaky.androidide.lookup.Lookup
import com.itsaky.androidide.lsp.api.AbstractServiceProvider
import com.itsaky.androidide.lsp.api.ICompletionProvider
import com.itsaky.androidide.lsp.api.IServerSettings
import com.itsaky.androidide.lsp.models.Command
import com.itsaky.androidide.lsp.models.CompletionData
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind.CLASS
import com.itsaky.androidide.lsp.models.CompletionItemKind.FIELD
import com.itsaky.androidide.lsp.models.CompletionItemKind.VALUE
import com.itsaky.androidide.lsp.models.CompletionParams
import com.itsaky.androidide.lsp.models.CompletionResult
import com.itsaky.androidide.lsp.models.CompletionResult.Companion.EMPTY
import com.itsaky.androidide.lsp.models.InsertTextFormat.SNIPPET
import com.itsaky.androidide.lsp.models.MatchLevel
import com.itsaky.androidide.lsp.models.MatchLevel.NO_MATCH
import com.itsaky.androidide.lsp.xml.utils.XmlUtils
import com.itsaky.androidide.lsp.xml.utils.XmlUtils.NodeType
import com.itsaky.androidide.lsp.xml.utils.XmlUtils.NodeType.ATTRIBUTE
import com.itsaky.androidide.lsp.xml.utils.XmlUtils.NodeType.ATTRIBUTE_VALUE
import com.itsaky.androidide.lsp.xml.utils.XmlUtils.NodeType.TAG
import com.itsaky.androidide.lsp.xml.utils.XmlUtils.NodeType.UNKNOWN
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.utils.CharSequenceReader
import com.itsaky.androidide.utils.ILogger
import com.itsaky.androidide.xml.resources.ResourceTableRegistry
import com.itsaky.androidide.xml.widgets.Widget
import com.itsaky.androidide.xml.widgets.WidgetTable
import com.itsaky.sdk.SDKInfo
import com.itsaky.xml.INamespace
import io.github.rosemoe.sora.text.ContentReference
import java.io.IOException
import java.io.Reader
import kotlin.math.max
import org.eclipse.lemminx.dom.DOMDocument
import org.eclipse.lemminx.dom.DOMParser
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager

/**
 * Completion provider for XMl files.
 *
 * @author Akash Yadav
 */
class XmlCompletionProvider
@JvmOverloads
constructor(private val sdkInfo: SDKInfo? = null, settings: IServerSettings) :
  AbstractServiceProvider(), ICompletionProvider {

  init {
    super.applySettings(settings)
  }

  private val log = ILogger.newInstance(javaClass.simpleName)

  override fun complete(params: CompletionParams): CompletionResult {
    return try {
      val namespace = INamespace.ANDROID
      val contents = toString(params.requireContents())
      val document =
        DOMParser.getInstance().parse(contents, namespace.uri, URIResolverExtensionManager())
      val type = XmlUtils.getNodeType(document, params.position.requireIndex())

      if (type == UNKNOWN) {
        log.warn("Unknown node type. CompletionParams:", params)
        return EMPTY
      }

      val prefix =
        XmlUtils.getPrefix(document, params.position.requireIndex(), type) ?: return EMPTY

      completeImpl(params, document, prefix, type)
    } catch (error: Throwable) {
      log.error("An error occurred while computing XML completions", error)
      EMPTY
    }
  }

  private fun toString(contents: CharSequence): String {
    val reader = getReader(contents)
    val text = reader.readText()
    try {
      reader.close()
    } catch (e: IOException) {
      log.warn("Unable to close char sequence reader", e)
    }
    return text
  }

  private fun getReader(contents: CharSequence): Reader =
    if (contents is ContentReference) {
      contents.createReader()
    } else {
      CharSequenceReader(contents)
    }

  private fun completeImpl(
    params: CompletionParams,
    document: DOMDocument,
    prefix: String,
    type: NodeType,
  ): CompletionResult {
    return when (type) {
      TAG ->
        completeTags(
          if (prefix.startsWith("<")) {
            prefix.substring(1)
          } else {
            prefix
          }
        )
      ATTRIBUTE -> completeAttributes(document, params.position)
      ATTRIBUTE_VALUE -> completeAttributeValue(document, prefix, params.position)
      else -> EMPTY
    }
  }

  private fun completeTags(prefix: String): CompletionResult {
    val widgets =
      Lookup.DEFAULT.lookup(WidgetTable.COMPLETION_LOOKUP_KEY)?.getAllWidgets() ?: return EMPTY
    val result = mutableListOf<CompletionItem>()

    for (widget in widgets) {
      val simpleNameMatchLevel = matchLevel(widget.simpleName, prefix)
      val nameMatchLevel = matchLevel(widget.qualifiedName, prefix)
      if (simpleNameMatchLevel == NO_MATCH && nameMatchLevel == NO_MATCH) {
        continue
      }

      val matchLevel =
        MatchLevel.values()[max(simpleNameMatchLevel.ordinal, nameMatchLevel.ordinal)]

      result.add(createTagCompletionItem(widget, matchLevel))
    }

    return CompletionResult(result)
  }

  private fun completeAttributes(document: DOMDocument, position: Position): CompletionResult {
    val node = document.findNodeAt(position.requireIndex())
    val styleables =
      Lookup.DEFAULT.lookup(ResourceTableRegistry.COMPLETION_FRAMEWORK_RES_LOOKUP_KEY)
        ?.findPackage("android")
        ?.findGroup(STYLEABLE)
        ?: run {
          log.debug("Cannot find styles in framework resources")
          return EMPTY
        }

    val nodeStyleables = findNodeStyleables(node.nodeName, styleables)
    if (nodeStyleables.isEmpty()) {
      return EMPTY
    }

    val list = mutableListOf<CompletionItem>()
    val attr = document.findAttrAt(position.requireIndex())
    for (nodeStyleable in nodeStyleables) {
      for (ref in nodeStyleable.entries) {
        val matchLevel = matchLevel(ref.name.entry, attr.name)
        if (matchLevel == NO_MATCH) {
          continue
        }
        list.add(createAttrCompletionItem(ref, matchLevel))
      }
    }

    return CompletionResult(list)
  }

  private fun findNodeStyleables(nodeName: String, styleables: ResourceGroup): Set<Styleable> {
    val widgets = Lookup.DEFAULT.lookup(WidgetTable.COMPLETION_LOOKUP_KEY) ?: return emptySet()
    val result = mutableSetOf<Styleable>()

    // Find the widget
    val widget =
      if (nodeName.contains(".")) {
        widgets.getWidget(nodeName)
      } else {
        widgets.findWidgetWithSimpleName(nodeName)
      }
        ?: return emptySet()

    // Find the <declare-styleable> for the widget in the resource group
    val entry = styleables.findEntry(widget.simpleName)?.findValue(ConfigDescription())?.value
    if (entry != null && entry is Styleable) {
      result.add(entry)
    }

    // Find styleables for all the superclasses
    for (superclass in widget.superclasses) {
      val superr = widgets.getWidget(superclass) ?: continue
      val superEntry =
        styleables.findEntry(superr.simpleName)?.findValue(ConfigDescription())?.value
      if (superEntry != null && superEntry is Styleable) {
        result.add(superEntry)
      }
    }

    return result
  }

  private fun completeAttributeValue(
    document: DOMDocument,
    prefix: String,
    position: Position
  ): CompletionResult {
    if (sdkInfo == null) {
      return EMPTY
    }

    val attr = document.findAttrAt(position.requireIndex())

    // TODO Provide attribute values based on namespace URI
    //   For example, if the package name of the namespace of this attribute refers to a library
    //   dependency/module, check for values in the respective dependency
    //   Currently, only the attributes from the 'android' package name are suggested

    val name = attr.localName ?: return EMPTY
    val attribute = sdkInfo.attrInfo.getAttribute(name) ?: return EMPTY
    val items = mutableListOf<CompletionItem>()
    for (value in attribute.possibleValues) {
      val matchLevel = matchLevel(value, prefix)

      // It might happen that the completion request is triggered but the prefix is empty
      // For example, a completion request is triggered when the user selects an attribute
      // completion item.
      // In such cases, 'prefix' is an empty string.
      // So, we still have to provide completions
      if (prefix.isEmpty() || matchLevel != NO_MATCH) {
        items.add(createAttrValueCompletionItem(attr.name, value, matchLevel))
      }
    }

    return CompletionResult(items)
  }

  private fun createTagCompletionItem(widget: Widget, matchLevel: MatchLevel): CompletionItem =
    CompletionItem().apply {
      this.label = widget.simpleName
      this.detail = widget.qualifiedName
      this.sortText = label.toString()
      this.matchLevel = matchLevel
      this.kind = CLASS
      this.data = CompletionData().apply { className = widget.qualifiedName }
    }

  private fun createAttrCompletionItem(attr: Reference, matchLevel: MatchLevel): CompletionItem =
    CompletionItem().apply {
      var pck = attr.name.pck
      if (pck == null || pck.isBlank()) {
        pck = "android"
      }

      this.label = attr.name.entry!!
      this.kind = FIELD
      this.detail = "From package '$pck'"
      this.insertText = "$pck:${attr.name.entry!!}=\"$0\""
      this.insertTextFormat = SNIPPET
      this.sortText = label.toString()
      this.matchLevel = matchLevel
      this.command = Command("Trigger completion request", Command.TRIGGER_COMPLETION)
    }

  private fun createAttrValueCompletionItem(
    attrName: String,
    value: String,
    matchLevel: MatchLevel
  ): CompletionItem {
    return CompletionItem().apply {
      this.label = value
      this.detail = "Value for '$attrName'"
      this.kind = VALUE
      this.sortText = label.toString()
      this.matchLevel = matchLevel
    }
  }
}
