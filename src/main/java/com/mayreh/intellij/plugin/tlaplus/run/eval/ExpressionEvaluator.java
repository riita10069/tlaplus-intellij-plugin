package com.mayreh.intellij.plugin.tlaplus.run.eval;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;

import tla2sany.semantic.ModuleNode;
import tla2sany.semantic.OpDefNode;
import tlc2.tool.EvalException;
import tlc2.tool.impl.FastTool;
import tlc2.tool.impl.Tool;
import util.Assert.TLCRuntimeException;
import util.FilenameToStream;
import util.SimpleFilenameToStream;
import util.ToolIO;

/**
 * Provides a feature to evaluate constant expression in given module context
 */
public class ExpressionEvaluator {
    private static final Logger LOG = Logger.getInstance(ExpressionEvaluator.class);

    /**
     * Evaluate an expression and return the value.
     * The implementation is almost taken from tla2tools's official {@link tlc2.REPL}.
     *
     * Please refer {@link IsolatedClassLoader}'s javadoc about why this method
     * loads {@link ExpressionEvaluator} from custom classloader.
     */
    public static Result evaluate(@Nullable Context context,
                                  String expression) {
        String clazzName = ExpressionEvaluator.class.getName();
        IsolatedClassLoader loader = new IsolatedClassLoader(
                ExpressionEvaluator.class.getClassLoader());
        try {
            Class<?> clazz = loader.loadClass(clazzName);
            Method method = clazz.getDeclaredMethod("evaluate0", Context.class, String.class);
            return (Result) method.invoke(null, context, expression);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Expected to be called only from {@link #evaluate}, but set to public
     * because {@link #evaluate}'s caller may be loaded from different class loader than
     * {@link IsolatedClassLoader}.
     */
    public static Result evaluate0(
            @Nullable Context context,
            String expression) {
        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("tla");
            Path dummyModuleFile = tmpDir.resolve(DummyModule.moduleName() + ".tla");
            Path dummyCfgFile = tmpDir.resolve(DummyModule.moduleName() + ".cfg");

            FileUtil.writeToFile(dummyModuleFile.toFile(), createDummyModule(context, expression));
            FileUtil.writeToFile(dummyCfgFile.toFile(), DummyModule.configFileContent());

            final Path moduleDirectory;
            if (context != null) {
                moduleDirectory = context.directory();
            } else {
                moduleDirectory = tmpDir;
            }

            // To collect error messages instead of writing to stdout
            ToolIO.setMode(ToolIO.TOOL);
            ToolIO.reset();
            Runner runner = new Runner(new FilenameResolver(moduleDirectory, new DummyModule(dummyModuleFile)));
            tlc2.module.TLC.OUTPUT = runner;

            return runner.run("replvalue");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            tlc2.module.TLC.OUTPUT = null;
            if (tmpDir != null) {
                delete(tmpDir);
            }
        }
    }

    private static String createDummyModule(@Nullable Context context, String expression) {
        DummyModule.Builder builder = DummyModule
                .builder()
                .setExpression(expression);
        if (context != null) {
            builder.extend(context.moduleName());
        }
        return builder.buildAsString();
    }

    private static void delete(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            LOG.warn("Failed to clean up directory", e);
        }
    }

    /**
     * Evaluate an operation in dummy module to get the value.
     * Also collect TLC's output which is printed by Print/PrintT
     */
    private static class Runner extends Writer {
        private final FilenameResolver resolver;
        private final StringWriter underlying;
        private boolean outputEnabled;

        Runner(FilenameResolver resolver) {
            this.resolver = resolver;
            underlying = new StringWriter();
            outputEnabled = true;
        }

        Result run(String opName) {
            List<String> errors = new ArrayList<>();

            String value = null;
            try {
                Tool tool = new FastTool(DummyModule.moduleName(), DummyModule.moduleName(), resolver);
                ModuleNode module = tool.getSpecProcessor().getRootModule();
                OpDefNode valueNode = module.getOpDef(opName);

                // PrintT will be output on parsing (when instantiating tool above).
                // We disable output right before evaluating the expression to suppress redundant output
                // on evaluation
                outputEnabled = false;
                value = ((tlc2.value.impl.Value) tool.eval(valueNode.getBody())).toString();
            } catch (EvalException e) {
                errors.add(e.getMessage());
            } catch (TLCRuntimeException e) {
                errors.add(e.getMessage());
                if (e.parameters != null) {
                    errors.addAll(Arrays.asList(e.parameters));
                }
            } finally {
                flush();
            }

            String output = Stream.of(underlying.toString(), value)
                                  .filter(StringUtil::isNotEmpty)
                                  .map(String::trim)
                                  .collect(Collectors.joining("\n"));
            return new Result(output, errors);
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            if (outputEnabled) {
                underlying.write(cbuf, off, len);
            }
        }

        @Override
        public void flush() {
            underlying.flush();
        }

        @Override
        public void close() throws IOException {
            underlying.close();
        }
    }

    /**
     * ClassLoader that loads ExpressionEvaluator and tla2tools-related classes from
     * jars to obtain initialized class everytime {@link ExpressionEvaluator#evaluate} called.
     *
     * As of tla2tools v1.7.1, TLC is not expected to be run multiple times on same JVM instance
     * because there are many places that mutating static fields.
     * refs: https://github.com/tlaplus/tlaplus/pull/425
     *
     * There are two workarounds for this:
     * 1. Fork JVM process when evaluating expression
     *   - This is simple, but might have significant overhead to start-up new JVM
     * 2. Use custom classloader to get initialized classes every time evaluating expression
     *   - This is the technique mentioned in tlaplus/tlaplus#424.
     *
     * We want to make expression-evaluation lightweight as much as possible, so we adopt 2.
     */
    private static class IsolatedClassLoader extends URLClassLoader {
        private final ClassLoader delegate;

        private final Map<String, Class<?>> cache = new HashMap<>();
        private final Set<String> prefixes = Set.of(
                "tla2sany",
                "pcal",
                "util",
                "tla2tex",
                "tlc2",
                ExpressionEvaluator.class.getName());

        IsolatedClassLoader(ClassLoader current) {
            super(new URL[]{jarURL(tlc2.TLC.class), jarURL(ExpressionEvaluator.class)});
            delegate = current;
        }

        private static URL jarURL(Class<?> clazz) {
            try {
                return PathManager.getJarForClass(clazz).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (cache.containsKey(name)) {
                return cache.get(name);
            }
            for (String pkg : prefixes) {
                if (name.startsWith(pkg)) {
                    Class<?> clazz = findClass(name);
                    cache.put(name, clazz);
                    return clazz;
                }
            }
            return delegate.loadClass(name);
        }
    }

    /**
     * A {@link FilenameToStream} impl that resolves dummy module to dummy directory and
     * otherwise just delegates to {@link SimpleFilenameToStream}.
     *
     * This is to apply different module name resolution logic.
     * Expression evaluation on specific module works like below:
     * - Create dummy module that extends the module in temporary directory
     * - Run TLC against dummy module
     * Hence, in normal resolution logic, dummy module and the imported module must be in same directory.
     * We don't want to create dummy module in module's directory, so we need custom resolver.
     */
    private static class FilenameResolver implements FilenameToStream {
        private final SimpleFilenameToStream delegate;
        private final DummyModule dummyModule;

        FilenameResolver(Path moduleDirectory,
                         DummyModule dummyModule) {
            delegate = new SimpleFilenameToStream(moduleDirectory.toAbsolutePath().toString());
            this.dummyModule = dummyModule;
        }

        @Override
        public File resolve(String name, boolean isModule) {
            final String nameWithoutExtension;
            if (name.contains(".")) {
                nameWithoutExtension = name.substring(0, name.lastIndexOf('.'));
            } else {
                nameWithoutExtension = name;
            }
            if (Objects.equals(nameWithoutExtension, DummyModule.moduleName())) {
                return dummyModule.moduleFile()
                                  .getParent()
                                  .resolve(name)
                                  .toFile();
            }
            return delegate.resolve(name, isModule);
        }

        @Override
        public String getFullPath() {
            return delegate.getFullPath();
        }

        @Override
        public boolean isStandardModule(String moduleName) {
            return delegate.isStandardModule(moduleName);
        }
    }
}
