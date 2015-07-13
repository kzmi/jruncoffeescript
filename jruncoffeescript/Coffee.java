// Copyright (c) 2015 Iwasa Kazmi
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package jruncoffeescript;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

public class Coffee {

	public static void main(String[] args) throws Exception {
		Coffee coffee = new Coffee();
		coffee.parseOptions(args);
		coffee.run();
	}

	private boolean optCompile = false;
	private boolean optHelp = false;
	private boolean optVersion = false;
	private boolean optSourceMap = false;
	private boolean optBare = false;
	private boolean optHeader = true;
	private boolean optLiterate = false;
	private String optOutputDir = null;
	private boolean optVerbose = false;
	private boolean optUpdate = false;
	private boolean optWatch = false;
	private String optClosure = null;

	private ScriptEngine engine;
	private final List<String> sourceFiles = new ArrayList<String>();

	private ScheduledThreadPoolExecutor delayedCompileSchedule = null;
	private Map<String, Object> delayedCompileTickets = null;

	private ClosureRunner closureRunner = null;

	private static final String EXTENSION_JS = ".js";
	private static final String EXTENSION_MAP = ".js.map";
	private static final String EXTENSION_JS_TMP = ".js.tmp";	// source of closure compiler

	private void initEngine() throws ScriptException, UnsupportedEncodingException, IOException {
		if (engine != null) {
			return;
		}

		verbose("initialize script engine");

		ScriptEngineManager manager = new ScriptEngineManager();
		engine = manager.getEngineByName("JavaScript");
		engine.eval(readCoffeeScriptCompiler());
	}

	public void parseOptions(String[] args) {
		for (int i = 0; i < args.length; ++i) {
			String opt = args[i];

			String[] subOpts;

			if (opt.startsWith("--")) {
				subOpts = new String[] { opt };
			} else if (opt.startsWith("-")) {
				subOpts = opt.substring(1).split("(?<!^)");
			} else {
				sourceFiles.add(opt);
				continue;
			}

			for (String subOpt : subOpts) {
				if ("--help".equals(subOpt) || "h".equals(subOpt)) {
					optHelp = true;
				} else if ("--version".equals(subOpt) || "v".equals(subOpt)) {
					optVersion = true;
				} else if ("--compile".equals(subOpt) || "c".equals(subOpt)) {
					optCompile = true;
				} else if ("--map".equals(subOpt) || "m".equals(subOpt)) {
					optSourceMap = true;
				} else if ("--bare".equals(subOpt) || "b".equals(subOpt)) {
					optBare = true;
				} else if ("--literate".equals(subOpt) || "l".equals(subOpt)) {
					optLiterate = true;
				} else if ("--no-header".equals(subOpt)) {
					optHeader = false;
				} else if ("--output".equals(subOpt) || "o".equals(subOpt)) {
					optOutputDir = (i + 1 < args.length) ? args[i + 1] : (String) null;
					++i;
				} else if ("--verbose".equals(subOpt)) {
					optVerbose = true;
				} else if ("--update".equals(subOpt)) {
					optUpdate = true;
				} else if ("--watch".equals(subOpt) || "w".equals(subOpt)) {
					optWatch = true;
				} else if ("--closure".equals(subOpt)) {
					optClosure = (i + 1 < args.length) ? args[i + 1] : (String) null;
					++i;
				} else {
					System.err.println(MessageFormat.format("Unknown option: {0}", subOpt));
					System.exit(1);
				}
			}
		}
	}

	public void run() throws Exception {
		if (optHelp) {
			showHelp();
			return;
		}

		if (optVersion) {
			showVersion();
			return;
		}

		if (!optCompile) {
			return;
		}

		if (optClosure != null) {
			closureRunner = new ClosureRunner(optClosure);
			if (optVerbose) {
				verbose("Closure Compiler options: {0}", closureRunner.getOptions());
			}
		}

		List<String> pathList = makePathList(sourceFiles);
		for (String path : pathList) {
			boolean succeeded = compile(path);
			if (!succeeded && !optWatch) {
				System.exit(1);
			}
		}

		if (optWatch) {
			watch(pathList);
		}
	}

	private void showHelp() {
		System.out.println("Usage: java " + getClass().getName() + " [options] [path/to/script.coffee ...]");
		System.out.println("  -b   --bare              compile without a top-level function wrapper");
		System.out.println("  -c   --compile           compile to JavaScript and save as .js files");
		System.out.println("  -h   --help              display this help message");
		System.out.println("  -m   --map               generate source map and save as .js.map files");
		System.out.println("       --no-header         suppress the \"Generated by\" header");
		System.out.println("       --output DIR        set the output directory for compiled JavaScript");
		System.out.println("  -l   --literate          treat input as literate style coffee-script");
		System.out.println("  -v   --version           display the version number");
		System.out.println("  -w   --watch             watch scripts for changes and rerun commands");
		System.out.println("       --verbose           output more informations to stdout");
		System.out.println("       --update            compile only if the source file is newer than the js file");
		System.out.println("       --closure OPTIONS   call Google's Closure Compiler (requires compiler.jar in CLASSPATH)");
	}

	private void showVersion() throws ScriptException, IOException {
		initEngine();

		Object obj = engine.eval("CoffeeScript.VERSION");
		System.out.println(MessageFormat.format("CoffeeScript version {0}", obj));

		System.out.println(MessageFormat.format("ScriptEngine: {0} {1}",
				engine.getFactory().getEngineName(),
				engine.getFactory().getEngineVersion()));
	}

	private List<String> makePathList(List<String> args) {
		List<String> pathList = new ArrayList<String>();
		for (String arg : args) {
			File f = new File(arg);
			if (f.isDirectory()) {
				searchSourceFiles(pathList, f);
			} else {
				pathList.add(arg);
			}
		}
		return pathList;
	}

	private void searchSourceFiles(List<String> pathList, File dir) {
		for (File f : dir.listFiles()) {
			if (f.isDirectory()) {
				searchSourceFiles(pathList, f);
			} else {
				String fileName = f.getName();
				if (fileName.endsWith(".coffee")
						|| fileName.endsWith(".litcoffee")
						|| fileName.endsWith(".coffee.md")) {

					pathList.add(f.getPath());
				}
			}
		}
	}

	private void watch(List<String> pathList) throws IOException {
		if (pathList.size() == 0) {
			return;
		}

		Set<String> pathSet = new HashSet<String>();	// paths in absolute form
		Set<String> dirs = new HashSet<String>();		// paths in absolute form
		for (String path : pathList) {
			File absPathFile = new File(path).getAbsoluteFile();
			pathSet.add(absPathFile.getPath());
			dirs.add(absPathFile.getParent());
		}

		FileSystem fs = FileSystems.getDefault();
		WatchService ws = fs.newWatchService();
		WatchEvent.Kind<?>[] eventKinds = {
				StandardWatchEventKinds.ENTRY_MODIFY
		};
		for (String dir : dirs) {
			Path pathObj = fs.getPath(dir);
			pathObj.register(ws, eventKinds);
		}

		// Sometimes watch-event comes twice when a file was updated.
		// We use delayed task to run compiler efficiently.
		prepareDelayedCompile();

		try {
			for (;;) {
				WatchKey key = ws.take();
				Path pathWatching = (Path) key.watchable();
				for (WatchEvent<?> watchEvent : key.pollEvents()) {
					Path target = (Path) watchEvent.context();
					String filePath = pathWatching.resolve(target).toString();
					if (pathSet.contains(filePath)) {
						scheduleCompile(filePath);
					}
				}
				key.reset();
			}
		} catch (InterruptedException e) {
			// ignore
		}

		shutdownDelayedCompile();
	}

	private void prepareDelayedCompile() {
		delayedCompileSchedule = new ScheduledThreadPoolExecutor(1);
		delayedCompileSchedule.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

		delayedCompileTickets = new ConcurrentHashMap<String, Object>();
	}

	private void shutdownDelayedCompile() {
		if (delayedCompileSchedule != null) {
			delayedCompileSchedule.shutdown();
		}
	}

	private void scheduleCompile(final String filePath) {
		final Object ticket = new Object();
		delayedCompileTickets.put(filePath, ticket);
		Runnable command = new Runnable() {
			@Override
			public void run() {
				if (ticket != delayedCompileTickets.get(filePath)) {
					// another command exists in the queue
					return;
				}

				if (!new File(filePath).exists()) {
					return;
				}

				try {
					compile(filePath);
				} catch (UnsupportedEncodingException e) {
					e.printStackTrace();
				} catch (ScriptException e) {
					e.printStackTrace();
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (URISyntaxException e) {
					e.printStackTrace();
				}
			}
		};
		delayedCompileSchedule.schedule(command, 500, TimeUnit.MILLISECONDS);
	}

	public boolean compile(String sourceFilePath) throws UnsupportedEncodingException, ScriptException, FileNotFoundException, IOException, URISyntaxException {
		initEngine();

		File sourceFile = new File(sourceFilePath).getAbsoluteFile();
		File jsFile = getFileToSave(sourceFile, EXTENSION_JS);		// target .js file
		File mapFile = getFileToSave(sourceFile, EXTENSION_MAP);
		File jsOutputFile;		// file to save JS code
		if (closureRunner != null) {
			jsOutputFile = getFileToSave(sourceFile, EXTENSION_JS_TMP);
		} else {
			jsOutputFile = jsFile;
		}

		if (optUpdate && !checkIfUpdated(sourceFile, jsFile)) {
			verbose("skip (js file is up-to-date): {0}", sourceFile);
			return true;	// no error
		}

		verbose("compile: {0}", sourceFile);

		engine.getContext().removeAttribute("csOptions", ScriptContext.ENGINE_SCOPE);
		engine.getContext().removeAttribute("jsOutput", ScriptContext.ENGINE_SCOPE);
		engine.getContext().removeAttribute("csError", ScriptContext.ENGINE_SCOPE);

		String source = readFile(sourceFile);
		engine.getContext().setAttribute("csSource", source, ScriptContext.ENGINE_SCOPE);

		// Some option values have to be a JavaScript object which has the "toJSON" method.
		// I don't know how to create a JavaScript object in Java code without using engine-specific classes.
		// So we use JavaScript code to build an object.
		String buildOption = getCodeToBuildOption("csOptions", sourceFile, jsFile, mapFile);
		engine.eval(buildOption);

		engine.eval("try { jsOutput = CoffeeScript.compile(csSource, csOptions) } catch(e) { csError = e.toString() }");

		Object jsOutput = engine.getContext().getAttribute("jsOutput", ScriptContext.ENGINE_SCOPE);
		Object csError = engine.getContext().getAttribute("csError", ScriptContext.ENGINE_SCOPE);

		if (csError != null) {
			System.err.println(csError.toString());
			return false;	// error
		}

		Object jsCompiled;
		Object jsMap;
		if (optSourceMap) {
			if (!(jsOutput instanceof Map<?, ?>)) {
				throw new IllegalStateException(MessageFormat.format("Unexpected result: {0}", jsOutput.getClass()));
			}
			jsCompiled = ((Map<?, ?>) jsOutput).get("js");
			jsMap = ((Map<?, ?>) jsOutput).get("v3SourceMap");
		} else {
			jsCompiled = jsOutput;
			jsMap = null;
		}

		if (jsCompiled == null) {
			throw new IllegalStateException(MessageFormat.format("Missing compiled code: {0}", jsOutput.getClass()));
		}

		if (optSourceMap && jsMap == null) {
			throw new IllegalStateException(MessageFormat.format("Missing source map: {0}", jsOutput.getClass()));
		}

		String jsCompiledStr = jsCompiled.toString();
		if (optSourceMap) {
			// map file is created in the same directory as the js file
			String mapPath = mapFile.getName();
			// String mapPath = getRelativePath(jsFile.getParentFile().getPath(), mapFile.getPath());
			String mapUrl = URLEncoder.encode(mapPath, "UTF-8").replace("+", "%20");
			jsCompiledStr += "\n//# sourceMappingURL=" + mapUrl + "\n";
		}

		verbose("  --> save js file: {0}", jsOutputFile);
		save(jsOutputFile, jsCompiledStr);

		if (optSourceMap) {
			verbose("  --> save map file: {0}", mapFile);
			save(mapFile, jsMap.toString());
		}

		if (closureRunner != null) {
			verbose("  --> compile with Closure Compiler: {0}", jsFile);
			boolean success = closureRunner.run(jsOutputFile, jsFile);
			jsOutputFile.delete();
			if (!success) {
				verbose("Error in Closure Compiler");
			}
		}

		return true;	// no error
	}

	private String readFile(File file) throws UnsupportedEncodingException, FileNotFoundException, IOException {
		FileInputStream s = new FileInputStream(file);
		byte[] content;
		try {
			content = new byte[(int) file.length()];
			s.read(content);
		} finally {
			s.close();
		}
		return new String(content, "UTF-8");
	}

	private String readCoffeeScriptCompiler() throws UnsupportedEncodingException, IOException {
		String scriptPath = "/coffee-script.js";
		if (engine.getFactory().getEngineName().contains("Rhino")) {
			// Rhino engine which is provided by JRE7 doesn't support "Using reserved word as a property name" feature in ECMAScript5.
			// It causes error at the property name "double".
			// We use a modified source file to avoid this issue.
			scriptPath = "/coffee-script-for-rhino.js";
			System.err.println("WARNING: use coffee-script-for-rhino.js");
		}

		verbose("compiler script: {0}", scriptPath);

		ByteArrayOutputStream o = new ByteArrayOutputStream();
		try {
			InputStream s = Coffee.class.getResourceAsStream(scriptPath);
			try {
				byte[] chunk = new byte[200000];
				for (;;) {
					int n = s.read(chunk);
					if (n <= 0) {
						break;
					}
					o.write(chunk, 0, n);
				}
			} finally {
				s.close();
			}
		} finally {
			o.close();
		}
		return o.toString("UTF-8");
	}

	private void save(File file, String text) throws UnsupportedEncodingException, FileNotFoundException, IOException {
		byte[] content = text.getBytes("UTF-8");
		FileOutputStream s = new FileOutputStream(file);
		try {
			s.write(content);
		} finally {
			s.close();
		}
	}

	private File getFileToSave(File sourceFile, String extension) {
		String sourceFileName = sourceFile.getName();
		String fileName;
		int dot = sourceFileName.lastIndexOf('.');
		if (dot >= 0) {
			fileName = sourceFileName.substring(0, dot) + extension;
		} else {
			fileName = sourceFileName + extension;
		}
		String dir;
		if (optOutputDir != null) {
			dir = optOutputDir;
		} else {
			dir = sourceFile.getParent();
		}
		return new File(dir, fileName).getAbsoluteFile();
	}

	private String getCodeToBuildOption(String veriable, File sourceFile, File jsFile, File mapFile) {
		String mapFileDir = mapFile.getParentFile().getPath();
		String jsFilePath = getRelativePath(mapFileDir, jsFile.getPath());

		List<String> sourceFiles = new ArrayList<String>();
		sourceFiles.add(getRelativePath(mapFileDir, sourceFile.getPath()));

		Map<String, Object> options = new HashMap<String, Object>();

		// compile options
		options.put("sourceMap", optSourceMap);
		options.put("bare", optBare);
		options.put("header", optHeader);
		options.put("literate", isLiterate(sourceFile) ? true : optLiterate);
		options.put("filename", sourceFile.getPath());

		// source map options
		options.put("generatedFile", jsFilePath);
		options.put("sourceRoot", "");
		options.put("sourceFiles", sourceFiles);

		return veriable + " = " + toJSON(options);
	}

	private String getRelativePath(String base, String target) {
		if (!base.endsWith(File.separator)) {
			base += File.separator;
		}
		String prefix = "";
		for (;;) {
			if (target.startsWith(base)) {
				return prefix + target.substring(base.length()).replace(File.separator, "/");
			}

			int sep = base.substring(0, base.length() - 1).lastIndexOf(File.separator);
			if (sep <= 0) {
				return target.replace(File.separator, "/");
			}
			base = base.substring(0, sep + 1);
			prefix = prefix + "../";
		}
	}

	private String toJSON(Map<?, ?> map) {
		StringBuilder s = new StringBuilder();
		s.append("{ ");
		String sep = "";
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String key = entry.getKey().toString();
			String value = toJSONValue(entry.getValue());
			s.append(sep).append("\"").append(key).append("\" : ").append(value);
			sep = ", ";
		}
		s.append(" }");
		return s.toString();
	}

	private String toJSON(List<?> list) {
		StringBuilder s = new StringBuilder();
		s.append("[ ");
		String sep = "";
		for (Object obj : list) {
			s.append(sep).append(toJSONValue(obj));
			sep = ", ";
		}
		s.append(" ]");
		return s.toString();
	}

	private String toJSONValue(Object obj) {
		if (obj instanceof Map<?, ?>) {
			return toJSON((Map<?, ?>) obj);
		}
		if (obj instanceof List<?>) {
			return toJSON((List<?>) obj);
		}
		if (obj instanceof String) {
			return "\"" + escapeJS((String) obj) + "\"";
		}
		if (obj instanceof Boolean) {
			return obj.toString();
		}
		if (obj == null) {
			return "null";
		}
		throw new IllegalArgumentException(obj.getClass().toString());
	}

	private String escapeJS(String text) {
		// escape only backslash and double quote
		return text.replaceAll("[\\\\\"]", "\\\\$0");
	}

	private boolean isLiterate(File sourceFile) {
		String path = sourceFile.getPath();
		if (path.endsWith(".litcoffee") || path.endsWith(".coffee.md")) {
			return true;
		} else {
			return false;
		}
	}

	private void verbose(String format, Object... params) {
		if (optVerbose) {
			System.out.println(MessageFormat.format(format, params));
		}
	}

	private boolean checkIfUpdated(File sourceFile, File jsFile) {
		if (jsFile.exists() && jsFile.lastModified() > sourceFile.lastModified()) {
			return false;
		} else {
			return true;
		}
	}

	private static class ClosureRunner {

		private static final String OPT_SOURCE_JS = "--js";
		private static final String OPT_OUTPUT_JS = "--js_output_file";

		private final String[] args;

		private final Class<?> commandLineRunnerClass;
		private final Constructor<?> commandLineRunnerCtor;
		private final Method commandLineRunnerRun;

		public ClosureRunner(String options) throws ClassNotFoundException, NoSuchMethodException {

			commandLineRunnerClass = Class.forName("com.google.javascript.jscomp.CommandLineRunner");
			commandLineRunnerCtor = commandLineRunnerClass.getDeclaredConstructor(String[].class);
			commandLineRunnerCtor.setAccessible(true);
			commandLineRunnerRun = commandLineRunnerClass.getSuperclass().getDeclaredMethod("doRun");
			commandLineRunnerRun.setAccessible(true);

			if (options == null) {
				args = new String[0];
				return;
			}

			String opt = options.trim();
			if (opt.length() == 0) {
				args = new String[0];
				return;
			}

			String argPattern = "(\"[^\"]*\"|[^\\s\"][^\\s]*)";
			Pattern pattern = Pattern.compile("^(?:" + argPattern + "(?:\\s+" + argPattern + ")*)?$", Pattern.DOTALL);
			Matcher matcher = pattern.matcher(opt);
			if (!matcher.matches()) {
				throw new IllegalArgumentException(MessageFormat.format("bad Closure Compiler options: {0}", options));
			}

			List<String> argsList = new ArrayList<String>();
			boolean skipNext = false;
			for (int gr = 1; gr <= matcher.groupCount(); ++gr) {
				System.out.println("Match: " + matcher.group(gr));
				if (skipNext) {
					skipNext = false;
					continue;
				}
				String arg = matcher.group(gr);
				if (OPT_SOURCE_JS.equals(arg) || OPT_OUTPUT_JS.equals(arg)) {
					skipNext = true;
					continue;
				}
				argsList.add(arg);
			}

			args = argsList.toArray(new String[argsList.size()]);

			System.out.println("Closure Options: " + options);
			for (String arg : args) {
				System.out.println("Closure Option: " + arg);
			}
		}

		public String getOptions() {
			StringBuilder options = new StringBuilder();
			String separator = "";
			for (String arg : args) {
				options.append(separator).append(arg);
				separator = " ";
			}
			return options.toString();
		}

		public boolean run(File sourceFile, File outputFile) {
			try {
				String[] newArgs = new String[args.length + 4];
				newArgs[0] = OPT_SOURCE_JS;
				newArgs[1] = sourceFile.getPath();
				newArgs[2] = OPT_OUTPUT_JS;
				newArgs[3] = outputFile.getPath();
				for (int i = 0; i < args.length; ++i) {
					newArgs[i + 4] = args[i];
				}

				Object runner = commandLineRunnerCtor.newInstance((Object) newArgs);
				Integer result = (Integer) commandLineRunnerRun.invoke(runner);

				return result == 0;
			} catch (Exception e) {
				e.printStackTrace();
				return false;
			}
		}
	}

}
