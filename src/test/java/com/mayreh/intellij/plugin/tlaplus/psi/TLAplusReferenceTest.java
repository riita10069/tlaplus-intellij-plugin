package com.mayreh.intellij.plugin.tlaplus.psi;

import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.junit.Assert;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.psi.PsiReference;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class TLAplusReferenceTest extends BasePlatformTestCase {
    @Override
    protected String getTestDataPath() {
        return "src/test/resources";
    }

    public void testBasic() {
        PsiReference reference = getReferenceAtCaret("Local.tla");

        TLAplusNonfixLhsName name = assertInstanceOf(reference.resolve(), TLAplusNonfixLhsName.class);
        Assert.assertEquals("Foo", name.getName());
    }

    public void testExtends() {
        PsiReference reference = getReferenceAtCaret("Extends_A.tla", "Extends_B.tla");

        TLAplusNonfixLhsName name = assertInstanceOf(reference.resolve(), TLAplusNonfixLhsName.class);
        Assert.assertEquals("Foo", name.getName());
        Assert.assertEquals("Extends_B", name.currentModule().getModuleHeader().getName());
    }

    public void testExtendsLocal() {
        PsiReference reference = getReferenceAtCaret("Extends_Local_A.tla", "Extends_Local_B.tla");
        // LOCAL definition should not be visible
        Assert.assertNull(reference.resolve());
    }

    public void testInstancePrefix() {
        PsiReference reference = getReferenceAtCaret(
                "InstancePrefix_A.tla",
                "InstancePrefix_B.tla",
                "InstancePrefix_C.tla");

        TLAplusNonfixLhsName name = assertInstanceOf(reference.resolve(), TLAplusNonfixLhsName.class);
        Assert.assertEquals("Foo", name.getName());
        Assert.assertEquals("InstancePrefix_C", name.currentModule().getModuleHeader().getName());
    }

    public void testCompletionStandardModules() {
        List<String> elements = getLookupElementStringsAtCaret("StandardModules.tla");
        Assert.assertNotNull(elements);
        assertSameElements(elements,
                           "Bags", "FiniteSets", "Integers", "Json",
                           "Naturals", "Randomization", "Reals", "RealTime",
                           "Sequences", "TLC", "TLCExt", "Toolbox");
    }

    private PsiReference getReferenceAtCaret(String... fileNames) {
        return myFixture.getReferenceAtCaretPositionWithAssertion(
                Arrays.stream(fileNames)
                      .map(name -> "tlaplus/psi/reference/fixtures/" + name)
                      .toArray(String[]::new)
        );
    }

    private @Nullable List<String> getLookupElementStringsAtCaret(String... fileNames) {
        myFixture.configureByFiles(
                Arrays.stream(fileNames)
                      .map(name -> "tlaplus/psi/completion/fixtures/" + name)
                      .toArray(String[]::new));
        myFixture.complete(CompletionType.BASIC);
        return myFixture.getLookupElementStrings();
    }
}
