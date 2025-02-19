package scala.meta.internal.pc

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

import scala.meta.internal.mtags.KeywordWrapper
import scala.meta.internal.mtags.MtagsEnrichments.*
import scala.meta.internal.pc.AutoImports.AutoImportEdits
import scala.meta.internal.pc.printer.ShortenedNames.ShortName
import scala.meta.pc.PresentationCompilerConfig

import dotty.tools.dotc.ast.tpd.*
import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.core.Denotations.*
import dotty.tools.dotc.core.Flags.*
import dotty.tools.dotc.core.Names.*
import dotty.tools.dotc.core.Symbols.*
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import org.eclipse.{lsp4j as l}

object AutoImports extends AutoImportsBackticks:

  object AutoImport:
    def renameConfigMap(config: PresentationCompilerConfig)(using
        Context
    ): Map[Symbol, String] =
      config.symbolPrefixes.asScala.flatMap { (from, to) =>
        val pkg = SemanticdbSymbols.inverseSemanticdbSymbol(from)
        val rename = to.stripSuffix(".").stripSuffix("#")
        List(pkg, pkg.map(_.moduleClass)).flatten
          .filter(_ != NoSymbol)
          .map((_, rename))
      }.toMap
  end AutoImport

  sealed trait SymbolIdent:
    def value: String

  object SymbolIdent:
    case class Direct(value: String) extends SymbolIdent
    case class Select(qual: SymbolIdent, name: String) extends SymbolIdent:
      def value: String = s"${qual.value}.$name"

    def direct(name: String): SymbolIdent = Direct(name)

    def fullIdent(symbol: Symbol)(using Context): SymbolIdent =
      val symbols = symbol.ownersIterator.toList
        .takeWhile(_ != ctx.definitions.RootClass)
        .reverse

      symbols match
        case head :: tail =>
          tail.foldLeft(direct(head.nameBackticked))((acc, next) =>
            Select(acc, next.nameBackticked)
          )
        case Nil =>
          SymbolIdent.direct("<no symbol>")

  end SymbolIdent

  sealed trait ImportSel:
    def sym: Symbol

  object ImportSel:
    final case class Direct(sym: Symbol) extends ImportSel
    final case class Rename(sym: Symbol, rename: String) extends ImportSel

  case class SymbolImport(
      sym: Symbol,
      ident: SymbolIdent,
      importSel: Option[ImportSel],
  ):

    def name: String = ident.value

  object SymbolImport:

    def simple(sym: Symbol)(using Context): SymbolImport =
      SymbolImport(sym, SymbolIdent.direct(sym.nameBackticked), None)

  /**
   * Returns AutoImportsGenerator
   *
   * @param pos A source position where the autoImport is invoked
   * @param text Source text of the file
   * @param tree A typed tree of the file
   * @param indexedContext A context of the position where the autoImport is invoked
   * @param config A presentation compiler config, this is used for renames
   */
  def generator(
      pos: SourcePosition,
      text: String,
      tree: Tree,
      indexedContext: IndexedContext,
      config: PresentationCompilerConfig,
  ): AutoImportsGenerator =

    import indexedContext.ctx

    val importPos = autoImportPosition(pos, text, tree)
    val renameConfig: Map[Symbol, String] = AutoImport.renameConfigMap(config)

    val renames =
      (sym: Symbol) =>
        indexedContext
          .rename(sym)
          .orElse(renameConfig.get(sym))

    new AutoImportsGenerator(
      pos,
      importPos,
      indexedContext,
      renames,
    )
  end generator

  case class AutoImportEdits(
      nameEdit: Option[l.TextEdit],
      importEdit: Option[l.TextEdit],
  ):

    def edits: List[l.TextEdit] = List(nameEdit, importEdit).flatten

  object AutoImportEdits:

    def apply(name: l.TextEdit, imp: l.TextEdit): AutoImportEdits =
      AutoImportEdits(Some(name), Some(imp))
    def importOnly(edit: l.TextEdit): AutoImportEdits =
      AutoImportEdits(None, Some(edit))
    def nameOnly(edit: l.TextEdit): AutoImportEdits =
      AutoImportEdits(Some(edit), None)

  /**
   * AutoImportsGenerator generates TextEdits of auto-imports
   * for the given symbols.
   *
   * @param pos A source position where the autoImport is invoked
   * @param importPosition A position to insert new imports
   * @param indexedContext A context of the position where the autoImport is invoked
   * @param renames A function that returns the name of the given symbol which is renamed on import statement.
   */
  class AutoImportsGenerator(
      val pos: SourcePosition,
      importPosition: AutoImportPosition,
      indexedContext: IndexedContext,
      renames: Symbol => Option[String],
  ):

    import indexedContext.ctx

    def forSymbol(symbol: Symbol): Option[List[l.TextEdit]] =
      editsForSymbol(symbol).map(_.edits)

    /**
     * Construct auto imports for the given ShortName,
     * if the shortName has different name with it's symbol name,
     * generate renamed import. For example,
     * `ShortName("ju", <java.util>)` => `import java.{util => ju}`.
     */
    def forShortName(shortName: ShortName): Option[List[l.TextEdit]] =
      if shortName.isRename then
        renderImports(
          List(ImportSel.Rename(shortName.symbol, shortName.name.show))
        ).map(List(_))
      else forSymbol(shortName.symbol)

    /**
     * @param symbol A missing symbol to auto-import
     */
    def editsForSymbol(symbol: Symbol): Option[AutoImportEdits] =
      val symbolImport = inferSymbolImport(symbol)
      val nameEdit = symbolImport.ident match
        case SymbolIdent.Direct(_) => None
        case other =>
          Some(new l.TextEdit(pos.toLsp, other.value))

      val importEdit =
        symbolImport.importSel.flatMap(sel => renderImports(List(sel)))
      if nameEdit.isDefined || importEdit.isDefined then
        Some(AutoImportEdits(nameEdit, importEdit))
      else None
    end editsForSymbol

    def inferSymbolImport(symbol: Symbol): SymbolImport =
      indexedContext.lookupSym(symbol) match
        case IndexedContext.Result.Missing =>
          // in java enum and enum case both have same flags
          val enumOwner = symbol.owner.companion
          def isJavaEnumCase: Boolean =
            symbol.isAllOf(EnumVal) && enumOwner.is(Enum)

          val (name, sel) =
            // For enums import owner instead of all members
            if symbol.isAllOf(EnumCase) || isJavaEnumCase
            then
              val ownerImport = inferSymbolImport(enumOwner)
              (
                SymbolIdent.Select(
                  ownerImport.ident,
                  symbol.nameBacktickedImport,
                ),
                ownerImport.importSel,
              )
            else
              (
                SymbolIdent.direct(symbol.nameBackticked),
                Some(ImportSel.Direct(symbol)),
              )
          end val

          SymbolImport(
            symbol,
            name,
            sel,
          )
        case IndexedContext.Result.Conflict =>
          val owner = symbol.owner
          renames(owner) match
            case Some(rename) =>
              val importSel =
                if rename != owner.showName then
                  Some(ImportSel.Rename(owner, rename)).filter(_ =>
                    !indexedContext.hasRename(owner, rename)
                  )
                else
                  Some(ImportSel.Direct(owner)).filter(_ =>
                    !indexedContext.lookupSym(owner).exists
                  )

              SymbolImport(
                symbol,
                SymbolIdent.Select(
                  SymbolIdent.direct(rename),
                  symbol.nameBacktickedImport,
                ),
                importSel,
              )
            case None =>
              SymbolImport(
                symbol,
                SymbolIdent.direct(symbol.fullNameBackticked),
                None,
              )
          end match
        case IndexedContext.Result.InScope =>
          val direct = renames(symbol).getOrElse(symbol.nameBackticked)
          SymbolImport(symbol, SymbolIdent.direct(direct), None)
      end match
    end inferSymbolImport

    def renderImports(
        imports: List[ImportSel]
    )(using Context): Option[l.TextEdit] =
      if imports.nonEmpty then
        val indent0 = " " * importPosition.indent
        val editPos = pos.withSpan(Spans.Span(importPosition.offset)).toLsp

        // for worksheets, we need to remove 2 whitespaces, because it ends up being wrapped in an object
        // see WorksheetProvider.worksheetScala3AdjustmentsForPC
        val indent =
          if pos.source.path.isWorksheet &&
            editPos.getStart().getCharacter() == 0
          then indent0.drop(2)
          else indent0
        val topPadding =
          if importPosition.padTop then "\n"
          else ""

        val formatted = imports
          .map {
            case ImportSel.Direct(sym) => importName(sym)
            case ImportSel.Rename(sym, rename) =>
              s"${importName(sym.owner)}.{${sym.nameBacktickedImport} => $rename}"
          }
          .map(sel => s"${indent}import $sel")
          .mkString(topPadding, "\n", "\n")

        Some(new l.TextEdit(editPos, formatted))
      else None
    end renderImports

    private def importName(sym: Symbol): String =
      if indexedContext.importContext.toplevelClashes(sym) then
        s"_root_.${sym.fullNameBacktickedImport}"
      else sym.fullNameBacktickedImport
  end AutoImportsGenerator

  private def autoImportPosition(
      pos: SourcePosition,
      text: String,
      tree: Tree,
  )(using Context): AutoImportPosition =

    @tailrec
    def lastPackageDef(
        prev: Option[PackageDef],
        tree: Tree,
    ): Option[PackageDef] =
      tree match
        case curr @ PackageDef(_, (next: PackageDef) :: Nil)
            if !curr.symbol.isPackageObject =>
          lastPackageDef(Some(curr), next)
        case pkg: PackageDef if !pkg.symbol.isPackageObject => Some(pkg)
        case _ => prev

    def firstObjectBody(tree: Tree)(using Context): Option[Template] =
      tree match
        case PackageDef(_, stats) =>
          stats.flatMap {
            case s: PackageDef => firstObjectBody(s)
            case TypeDef(_, t @ Template(defDef, _, _, _))
                if defDef.symbol.showName == "<init>" =>
              Some(t)
            case _ => None
          }.headOption
        case _ => None

    def forScalaSource: Option[AutoImportPosition] =
      lastPackageDef(None, tree).map { pkg =>
        val lastImportStatement =
          pkg.stats.takeWhile(_.isInstanceOf[Import]).lastOption
        val (lineNumber, padTop) = lastImportStatement match
          case Some(stm) => (stm.endPos.line + 1, false)
          case None if pkg.pid.symbol.isEmptyPackage =>
            val offset =
              ScriptFirstImportPosition.skipUsingDirectivesOffset(text)
            (pos.source.offsetToLine(offset), false)
          case None =>
            val pos = pkg.pid.endPos
            val line =
              // pos point at the last NL
              if pos.endColumn == 0 then math.max(0, pos.line - 1)
              else pos.line + 1
            (line, true)
        val offset = pos.source.lineToOffset(lineNumber)
        new AutoImportPosition(offset, text, padTop)
      }

    def forScript(isAmmonite: Boolean): Option[AutoImportPosition] =
      firstObjectBody(tree).map { tmpl =>
        val lastImportStatement =
          tmpl.body.takeWhile(_.isInstanceOf[Import]).lastOption
        val offset = lastImportStatement match
          case Some(stm) =>
            val offset = pos.source.lineToOffset(stm.endPos.line + 1)
            offset
          case None =>
            val scriptOffset =
              if isAmmonite then
                ScriptFirstImportPosition.ammoniteScStartOffset(text)
              else ScriptFirstImportPosition.scalaCliScStartOffset(text)

            scriptOffset.getOrElse(
              pos.source.lineToOffset(tmpl.self.srcPos.line)
            )
        new AutoImportPosition(offset, text, false)
      }
    end forScript

    val path = pos.source.path

    def fileStart =
      AutoImportPosition(
        ScriptFirstImportPosition.skipUsingDirectivesOffset(text),
        0,
        padTop = false,
      )

    val scriptPos =
      if path.isAmmoniteGeneratedFile then forScript(isAmmonite = true)
      else if path.isScalaCLIGeneratedFile then forScript(isAmmonite = false)
      else None

    scriptPos
      .orElse(forScalaSource)
      .getOrElse(fileStart)
  end autoImportPosition

end AutoImports

trait AutoImportsBackticks:
  // Avoids backticketing import parts that match soft keywords
  extension (sym: Symbol)(using Context)
    def fullNameBacktickedImport: String =
      sym.fullNameBackticked(KeywordWrapper.Scala3SoftKeywords)
    def nameBacktickedImport: String =
      sym.nameBackticked(KeywordWrapper.Scala3SoftKeywords)
