package com.mayreh.intellij.plugin.tlaplus.psi.ext;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.mayreh.intellij.plugin.tlaplus.psi.TLAplusElementTypes;
import com.mayreh.intellij.plugin.tlaplus.psi.TLAplusNamedElement;
import com.mayreh.intellij.plugin.tlaplus.psi.TLAplusPsiFactory;

public abstract class TLAplusNamedElementImpl extends TLAplusElementImpl implements TLAplusNamedElement {
    protected TLAplusNamedElementImpl(@NotNull ASTNode node) {
        super(node);
    }

    @Override
    public @Nullable PsiElement getNameIdentifier() {
        return findChildByType(TLAplusElementTypes.IDENTIFIER);
    }

    @Override
    public PsiElement setName(@NotNull String name) {
        PsiElement identifier = getNameIdentifier();
        if (identifier != null) {
            identifier.replace(new TLAplusPsiFactory(getProject()).createIdentifier(name));
        }
        return this;
    }

    @Override
    public int getTextOffset() {
        PsiElement identifier = getNameIdentifier();
        return identifier != null ? identifier.getTextOffset() : super.getTextOffset();
    }

    @Override
    public String getName() {
        PsiElement identifier = getNameIdentifier();
        return identifier != null ? identifier.getText() : super.getName();
    }

    /**
     * Overriding getUseScope is mandatory to make in-place refactoring works.
     * refs: https://intellij-support.jetbrains.com/hc/en-us/community/posts/360006918740-How-do-I-enable-in-place-rename-Renaming-via-dialog-works-fine-
     */
    @Override
    public @NotNull SearchScope getUseScope() {
        if (getContext() != null) {
            if (getContext().getContext() != null) {
                return new LocalSearchScope(getContext().getContext());
            }
        }
        return super.getUseScope();
    }
}
