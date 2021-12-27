
package com.mayreh.intellij.plugin.tlaplus.ide.actions;

import java.nio.file.Path;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProviderBase;
import com.intellij.xdebugger.impl.breakpoints.XExpressionImpl;
import com.mayreh.intellij.plugin.tlaplus.TLAplusFile;
import com.mayreh.intellij.plugin.tlaplus.TLAplusLanguage;
import com.mayreh.intellij.plugin.tlaplus.fragment.TLAplusFragmentFile;
import com.mayreh.intellij.plugin.tlaplus.fragment.TLAplusFragmentFileType;
import com.mayreh.intellij.plugin.tlaplus.fragment.TLAplusFragmentLanguage;
import com.mayreh.intellij.plugin.tlaplus.psi.TLAplusModule;
import com.mayreh.intellij.plugin.tlaplus.run.eval.Context;
import com.mayreh.intellij.plugin.tlaplus.run.eval.DummyModule;
import com.mayreh.intellij.plugin.tlaplus.run.eval.EvaluateExpressionDialog;

public class EvaluateExpressionAction extends TLAplusActionBase {
    @Override
    protected void doAction(@NotNull AnActionEvent e,
                            @NotNull Project project,
                            @NotNull TLAplusDocument document) {
        TLAplusModule currentModule = PsiTreeUtil.findChildOfType(document.file(), TLAplusModule.class);
        final String currentModuleName;
        if (currentModule != null) {
            currentModuleName = currentModule.getModuleHeader().getName();
        } else {
            currentModuleName = null;
        }

        Pair<Context, PsiDirectory> context = maybeContext(currentModuleName, document);
        FileDocumentManager.getInstance().saveAllDocuments();

        new EvaluateExpressionDialog(project, context == null ? null : context.first, new XDebuggerEditorsProviderBase() {
            @Override
            protected PsiFile createExpressionCodeFragment(
                    @NotNull Project project,
                    @NotNull String text,
                    @Nullable PsiElement elementContext,
                    boolean isPhysical) {

                StringBuilder moduleBuilder = new StringBuilder();
                moduleBuilder
                        .append("---- MODULE ").append(DummyModule.moduleName()).append(" ----").append('\n')
                        .append("EXTENDS Reals,Sequences,Bags,FiniteSets,TLC,Randomization");
                if (currentModuleName != null) {
                    moduleBuilder.append(',' + currentModuleName);
                }
                moduleBuilder.append('\n');
                moduleBuilder.append("====");

                String dummyFileName = DummyModule.moduleName() + ".tla";
                TLAplusFile dummyModuleFile = (TLAplusFile) PsiFileFactory
                        .getInstance(project)
                        .createFileFromText(dummyFileName,
                                            TLAplusLanguage.INSTANCE,
                                            moduleBuilder.toString());

                TLAplusFragmentFile fragment = (TLAplusFragmentFile) PsiFileFactory
                        .getInstance(project)
                        .createFileFromText(dummyFileName,
                                            TLAplusFragmentLanguage.INSTANCE,
                                            text);
                // TODO add comment why we can't use source module itself as context
                TLAplusModule dummyModule = PsiTreeUtil.findChildOfType(dummyModuleFile, TLAplusModule.class);
                if (dummyModule != null && context != null) {
                    fragment.setModule(dummyModule);
                    dummyModuleFile.setDirectory(context.second);
                }
                return fragment;
            }

            @Override
            public @NotNull FileType getFileType() {
                return TLAplusFragmentFileType.INSTANCE;
            }
        }, XExpressionImpl.EMPTY_EXPRESSION).show();
    }

    private static @Nullable Pair<Context, PsiDirectory> maybeContext(
            @Nullable String moduleName,
            TLAplusDocument document) {
        if (moduleName == null) {
            return null;
        }

        VirtualFile virtualFile = document.file().getVirtualFile();
        if (virtualFile == null) {
            return null;
        }

        PsiDirectory directory = document.file().getContainingDirectory();
        if (directory == null) {
            return null;
        }

        Path path = virtualFile.getFileSystem().getNioPath(directory.getVirtualFile());
        if (path == null) {
            return null;
        }

        return Pair.pair(new Context(moduleName, path), directory);
    }
}
