package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompanionCommandCompilationTest {

	@TempDir
	Path output;

	@Test
	void dynamicallyLoadedAiCommandsCompileAgainstCurrentServerApi() throws Exception {
		var compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "The game server requires a JDK to compile its handlers");
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
			var sources = files.getJavaFileObjectsFromPaths(List.of(Path.of("data/handlers/admincommands/AiPlayer.java"),
				Path.of("data/handlers/admincommands/Companion.java")));
			String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
			boolean compiled = compiler.getTask(null, files, diagnostics, List.of("-proc:none", "-classpath", classpath, "-d", output.toString()),
				null, sources).call();
			assertTrue(compiled, () -> diagnostics.getDiagnostics().stream().map(Object::toString).collect(Collectors.joining("\n")));
		}
	}
}
