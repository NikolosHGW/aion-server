package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Properties;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aionemu.gameserver.configs.administration.CommandsConfig;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;

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
		Properties properties = new Properties();
		try (var input = Files.newInputStream(Path.of("config/administration/commands.properties"))) {
			properties.load(input);
		}
		var previous = CommandsConfig.ACCESS_LEVELS;
		try (var loader = new URLClassLoader(new java.net.URL[] { output.toUri().toURL() }, getClass().getClassLoader())) {
			CommandsConfig.ACCESS_LEVELS = Map.of("companion", Byte.parseByte(properties.getProperty("companion").trim()),
				"aiplayer", Byte.parseByte(properties.getProperty("aiplayer").trim()));
			var companion = (AdminCommand) loader.loadClass("admincommands.Companion").getConstructor().newInstance();
			var ordinaryPlayer = ServerControlledPlayerRegistryTest.player(201);
			assertFalse(ordinaryPlayer.isStaff());
			assertTrue(companion.validateAccess(ordinaryPlayer));
			var route = (AdminCommand) loader.loadClass("admincommands.AiPlayer").getConstructor().newInstance();
			assertEquals(9, route.getLevel(), "Global route diagnostics remain administrative");
		} finally {
			CommandsConfig.ACCESS_LEVELS = previous;
		}
	}
}
