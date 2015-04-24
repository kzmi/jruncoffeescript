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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	private ScriptEngine engine;
	private final List<String> sourceFiles = new ArrayList<String>();

	private static final String EXTENSION_JS = ".js";
	private static final String EXTENSION_MAP = ".js.map";

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

		for (String file : sourceFiles) {
			compile(file);
		}
	}

	private void showHelp() {
		System.out.println("Usage: java " + getClass().getName() + " [options] [path/to/script.coffee ...]");
		System.out.println("  -b   --bare         compile without a top-level function wrapper");
		System.out.println("  -c   --compile      compile to JavaScript and save as .js files");
		System.out.println("  -h   --help         display this help message");
		System.out.println("  -m   --map          generate source map and save as .js.map files");
		System.out.println("       --no-header    suppress the \"Generated by\" header");
		System.out.println("       --output [DIR] set the output directory for compiled JavaScript");
		System.out.println("  -l   --literate     treat input as literate style coffee-script");
		System.out.println("  -v   --version      display the version number");
		System.out.println("       --verbose      output more informations to stdout");
		System.out.println("       --update       compile only if the source file is newer than the js file");
	}

	private void showVersion() throws ScriptException, IOException {
		initEngine();

		Object obj = engine.eval("CoffeeScript.VERSION");
		System.out.println(MessageFormat.format("CoffeeScript version {0}", obj));

		System.out.println(MessageFormat.format("ScriptEngine: {0} {1}",
				engine.getFactory().getEngineName(),
				engine.getFactory().getEngineVersion()));
	}

	public void compile(String sourceFilePath) throws UnsupportedEncodingException, ScriptException, FileNotFoundException, IOException, URISyntaxException {
		initEngine();

		File sourceFile = new File(sourceFilePath).getAbsoluteFile();
		File jsFile = getFileToSave(sourceFile, EXTENSION_JS);
		File mapFile = getFileToSave(sourceFile, EXTENSION_MAP);

		if (optUpdate && !checkIfUpdated(sourceFile, jsFile)) {
			verbose("skip (js file is up-to-date): {0}", sourceFile);
			return;
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
			System.exit(1);
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

		verbose("  --> save js file: {0}", jsFile);
		save(jsFile, jsCompiledStr);

		if (optSourceMap) {
			verbose("  --> save map file: {0}", mapFile);
			save(mapFile, jsMap.toString());
		}
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
}
