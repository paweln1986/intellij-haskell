// This is a generated file. Not intended for manual editing.
package intellij.haskell.psi;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public interface HaskellCdecls extends HaskellCompositeElement {

  @NotNull
  List<HaskellCdeclDataDeclaration> getCdeclDataDeclarationList();

  @NotNull
  List<HaskellCideclExpression> getCideclExpressionList();

  @NotNull
  List<HaskellDataDeclaration> getDataDeclarationList();

  @NotNull
  List<HaskellDefaultDeclaration> getDefaultDeclarationList();

  @NotNull
  List<HaskellInlinelikePragma> getInlinelikePragmaList();

  @NotNull
  List<HaskellInstanceDeclaration> getInstanceDeclarationList();

  @NotNull
  List<HaskellMinimalPragma> getMinimalPragmaList();

  @NotNull
  List<HaskellNewtypeDeclaration> getNewtypeDeclarationList();

  @NotNull
  List<HaskellSpecializePragma> getSpecializePragmaList();

  @NotNull
  List<HaskellTypeDeclaration> getTypeDeclarationList();

  @NotNull
  List<HaskellTypeFamilyDeclaration> getTypeFamilyDeclarationList();

  @NotNull
  List<HaskellTypeSignature> getTypeSignatureList();

}
