package fiji;

/**
 * Modify some IJ1 quirks at runtime, thanks to Javassist
 */

import java.io.File;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;
import javassist.Modifier;
import javassist.NotFoundException;

import javassist.bytecode.BadBytecode;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;

import javassist.expr.ConstructorCall;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;
import javassist.expr.Handler;
import javassist.expr.MethodCall;
import javassist.expr.NewExpr;

public class IJHacker implements Runnable {
	public final static String appName = "Fiji";

	protected String replaceAppName = ".replace(\"ImageJ\", \"" + appName + "\")";

	public void run() {
		try {
			ClassPool pool = ClassPool.getDefault();
			CtClass clazz;
			CtMethod method;
			CtField field;

			// Class ij.ImagePlus
			clazz = pool.get("ij.ImagePlus");

			try {
				// add back the (deprecated) killProcessor(), and overlay methods
				method = CtNewMethod.make("public void killProcessor() {}", clazz);
				clazz.addMethod(method);
			} catch (Exception e) { /* ignore */ }
			try {
				method = CtNewMethod.make("public void setDisplayList(java.util.Vector list) {"
					+ "  getCanvas().setDisplayList(list);"
					+ "}", clazz);
				clazz.addMethod(method);
			} catch (Exception e) { /* ignore */ }
			try {
				method = CtNewMethod.make("public java.util.Vector getDisplayList() {"
					+ "  return getCanvas().getDisplayList();"
					+ "}", clazz);
				clazz.addMethod(method);
			} catch (Exception e) { /* ignore */ }
			try {
				method = CtNewMethod.make("public void setDisplayList(ij.gui.Roi roi, java.awt.Color strokeColor, int strokeWidth, java.awt.Color fillColor) {"
					+ "  setOverlay(roi, strokeColor, strokeWidth, fillColor);"
					+ "}", clazz);
				clazz.addMethod(method);
			} catch (Exception e) { /* ignore */ }

			clazz.toClass();

			// Class ij.IJ
			clazz = pool.get("ij.IJ");

			boolean isImageJA = false;
			try {
				method = clazz.getMethod("runFijiEditor", "(Ljava/lang/String;Ljava/lang/String;)Z");
				isImageJA = true;
			} catch (Exception e) { /* ignore */ }

			// tell runUserPlugIn() to mention which class was not found if a dependency is missing
			method = clazz.getMethod("runUserPlugIn",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/Object;");
			method.instrument(new ExprEditor() {
				@Override
				public void edit(Handler handler) throws CannotCompileException {
					try {
						if (handler.getType().getName().equals("java.lang.NoClassDefFoundError"))
							handler.insertBefore("String cause = $1.getMessage();"
							+ "int index = cause.indexOf('(') + 1;"
							+ "int endIndex = cause.indexOf(')', index);"
							+ "if (!suppressPluginNotFoundError && index > 0 && endIndex > index) {"
							+ "  String name = cause.substring(index, endIndex);"
							+ "  error(\"Did not find required class: \" + $1.getMessage());"
							+ "  return null;"
							+ "}");
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});
			// tell the error() method to use "Fiji" as window title
			method = clazz.getMethod("error",
				"(Ljava/lang/String;Ljava/lang/String;)V");
			method.insertBefore("if ($1 == null || $1.equals(\"ImageJ\")) $1 = \"" + appName + "\";");
			// make sure that ImageJ has been initialized in batch mode
			method = clazz.getMethod("runMacro", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
			method.insertBefore("if (ij==null && ij.Menus.getCommands()==null) init();");

			clazz.toClass();

			// Class ij.gui.GenericDialog
			clazz = pool.get("ij.gui.GenericDialog");

			// make sure that the dialog is disposed in macro mode
			method = clazz.getMethod("showDialog", "()V");
			method.insertBefore("if (macro) dispose();");

			clazz.toClass();

			// Class ij.gui.NonBlockingGenericDialog
			clazz = pool.get("ij.gui.NonBlockingGenericDialog");

			// make sure not to wait in macro mode
			method = clazz.getMethod("showDialog", "()V");
			method.instrument(new ExprEditor() {
				@Override
				public void edit(MethodCall call) throws CannotCompileException {
					if (call.getMethodName().equals("wait"))
						call.replace("if (isShowing()) wait();");
				}
			});

			clazz.toClass();

			// Class ij.ImageJ
			clazz = pool.get("ij.ImageJ");

			// tell the superclass java.awt.Frame that the window title is "Fiji"
			for (CtConstructor ctor : clazz.getConstructors())
				ctor.instrument(new ExprEditor() {
					@Override
					public void edit(ConstructorCall call) throws CannotCompileException {
						if (call.getMethodName().equals("super"))
							call.replace("super(\"" + appName + "\");");
					}
				});
			// tell the version() method to prefix the version with "Fiji/"
			method = clazz.getMethod("version", "()Ljava/lang/String;");
			method.insertAfter("$_ = \"" + appName + "/\" + $_;");
			// tell the run() method to use "Fiji" instead of "ImageJ" in the Quit dialog
			method = clazz.getMethod("run", "()V");
			replaceAppNameInNew(method, "ij.gui.GenericDialog", 1, 2);
			replaceAppNameInCall(method, "addMessage", 1, 1);
			// use our icon
			method = clazz.getMethod("setIcon", "()V");
			method.instrument(new ExprEditor() {
				@Override
				public void edit(MethodCall call) throws CannotCompileException {
					if (call.getMethodName().equals("getResource"))
						call.replace("$_ = $0.getResource(\"/icon.png\");");
				}
			});
			if (!isImageJA) {
				clazz.getConstructor("(Ljava/applet/Applet;I)V").insertBeforeBody("if ($2 != ij.ImageJ.NO_SHOW) setIcon();");
				method = clazz.getMethod("isRunning", "([Ljava/lang/String;)Z");
				method.insertBefore("return fiji.OtherInstance.sendArguments($1);");
			}

			clazz.toClass();

			// Class ij.Prefs
			clazz = pool.get("ij.Prefs");

			// use Fiji instead of ImageJ
			clazz.getField("vistaHint").setName("originalVistaHint");
			field = new CtField(pool.get("java.lang.String"), "vistaHint", clazz);
			field.setModifiers(Modifier.STATIC | Modifier.PUBLIC | Modifier.FINAL);
			clazz.addField(field, "originalVistaHint" + replaceAppName + ";");
			// do not use the current directory as IJ home on Windows
			String prefsDir = System.getenv("IJ_PREFS_DIR");
			if (prefsDir == null && System.getProperty("os.name").startsWith("Windows"))
				prefsDir = System.getenv("user.home");
			if (prefsDir != null) {
				final String replace = "prefsDir = \"" + prefsDir + "\";";
				method = clazz.getMethod("load", "(Ljava/lang/Object;Ljava/applet/Applet;)Ljava/lang/String;");
				method.instrument(new ExprEditor() {
					@Override
					public void edit(FieldAccess access) throws CannotCompileException {
						if (access.getFieldName().equals("prefsDir") && access.isWriter())
							access.replace(replace);
					}
				});
			}

			clazz.toClass();

			// Class ij.gui.YesNoCancelDialog
			clazz = pool.get("ij.gui.YesNoCancelDialog");

			// use Fiji as window title in the Yes/No dialog
			for (CtConstructor ctor : clazz.getConstructors())
				ctor.instrument(new ExprEditor() {
					@Override
					public void edit(ConstructorCall call) throws CannotCompileException {
						if (call.getMethodName().equals("super"))
							call.replace("super($1, \"ImageJ\".equals($2) ? \"" + appName + "\" : $2, $3);");
					}
				});

			clazz.toClass();

			// Class ij.gui.Toolbar
			clazz = pool.get("ij.gui.Toolbar");

			// use Fiji/ImageJ in the status line
			method = clazz.getMethod("showMessage", "(I)V");
			method.instrument(new ExprEditor() {
				@Override
				public void edit(MethodCall call) throws CannotCompileException {
					if (call.getMethodName().equals("showStatus"))
						call.replace("if ($1.startsWith(\"ImageJ \")) $1 = \"" + appName + "/\" + $1;"
							+ "ij.IJ.showStatus($1);");
				}
			});
			// tool names can be prefixes of other tools, watch out for that!
			method = clazz.getMethod("getToolId", "(Ljava/lang/String;)I");
			method.instrument(new ExprEditor() {
				@Override
				public void edit(MethodCall call) throws CannotCompileException {
					if (call.getMethodName().equals("startsWith"))
						call.replace("$_ = $0.equals($1) || $0.startsWith($1 + \"-\") || $0.startsWith($1 + \" -\");");
				}
			});
			handleMousePressed(clazz);

			clazz.toClass();

			// Class ij.plugin.CommandFinder
			clazz = pool.get("ij.plugin.CommandFinder");

			// use Fiji in the window title
			method = clazz.getMethod("export", "()V");
			replaceAppNameInNew(method, "ij.text.TextWindow", 1, 5);

			clazz.toClass();

			// Class ij.plugin.Hotkeys
			clazz = pool.get("ij.plugin.Hotkeys");

			// Replace application name in removeHotkey()
			method = clazz.getMethod("removeHotkey", "()V");
			replaceAppNameInCall(method, "addMessage", 1, 1);
			replaceAppNameInCall(method, "showStatus", 1, 1);

			clazz.toClass();

			// Class ij.plugin.Options
			clazz = pool.get("ij.plugin.Options");

			// Replace application name in restart message
			method = clazz.getMethod("appearance", "()V");
			replaceAppNameInCall(method, "showMessage", 2, 2);

			clazz.toClass();

			// Class JavaScriptEvaluator
			clazz = pool.get("JavaScriptEvaluator");

			// make sure Rhino gets the correct class loader
			method = clazz.getMethod("run", "()V");
			method.insertBefore("Thread.currentThread().setContextClassLoader(ij.IJ.getClassLoader());");

			clazz.toClass();

			// Class ij.CompositeImage
			clazz = pool.get("ij.CompositeImage");

			// ImageJA had this public method
			method = CtNewMethod.make("public ij.ImagePlus[] splitChannels(boolean closeAfter) {"
				+ "  ij.ImagePlus[] result = ij.plugin.ChannelSplitter.split(this);"
				+ "  if (closeAfter) close();"
				+ "  return result;"
				+ "}", clazz);
			if (!isImageJA)
				clazz.addMethod(method);

			clazz.toClass();

			// Class ij.plugin.filter.RGBStackSplitter
			clazz = pool.get("ij.plugin.filter.RGBStackSplitter");

			// add back the splitChannesToArray() method
			method = CtNewMethod.make("public static ij.ImagePlus[] splitChannelsToArray(ij.ImagePlus imp, boolean closeAfter) {"
				+ "  if (!imp.isComposite()) {"
				+ "    ij.IJ.error(\"splitChannelsToArray was called on a non-composite image\");"
				+ "    return null;"
				+ "  }"
				+ "  ij.ImagePlus[] result = ij.plugin.ChannelSplitter.split(imp);"
				+ "  if (closeAfter)"
				+ "    imp.close();"
				+ "  return result;"
				+ "}", clazz);
			if (!isImageJA)
				clazz.addMethod(method);

			clazz.toClass();

			// Class ij.io.Opener
			clazz = pool.get("ij.io.Opener");

			// make sure that the check for Bio-Formats is correct
			clazz.getClassInitializer().instrument(new ExprEditor() {
				@Override
				public void edit(FieldAccess access) throws CannotCompileException {
					if (access.getFieldName().equals("bioformats") && access.isWriter())
						access.replace("bioformats = ij.IJ.getClassLoader().loadClass(\"loci.plugins.LociImporter\") != null;");
				}
			});
			// open text in the Fiji Editor
			method = clazz.getMethod("open", "(Ljava/lang/String;)V");
			method.insertBefore("if ($1.indexOf(\"://\") < 0 && isText($1) &&"
				+ "    ij.IJ.runPlugIn(\"fiji.scripting.Script_Editor\", $1) != null)"
				+ "  return;");

			clazz.toClass();

			// Class ij.macro.Interpreter
			clazz = pool.get("ij.macro.Interpreter");

			// make sure no dialog is opened in headless mode
			method = clazz.getMethod("showError", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V");
			method.insertBefore("if (ij.IJ.getInstance() == null) {"
				+ "  java.lang.System.err.println($1 + \": \" + $2);"
				+ "  return;"
				+ "}");

			clazz.toClass();

			// Class ij.plugin.DragAndDrop
			clazz = pool.get("ij.plugin.DragAndDrop");

			// make sure that symlinks are _not_ resolved (because then the parent info in the FileInfo would be wrong)
			method = clazz.getMethod("openFile", "(Ljava/io/File;)V");
			method.instrument(new ExprEditor() {
				@Override
				public void edit(MethodCall call) throws CannotCompileException {
					if (call.getMethodName().equals("getCanonicalPath"))
						call.replace("$_ = $0.getAbsolutePath();");
				}
			});
			handleHTTPS(clazz.getMethod("drop", "(Ljava/awt/dnd/DropTargetDropEvent;)V"));

			clazz.toClass();

			// Class ij.plugin.Commands
			clazz = pool.get("ij.plugin.Commands");

			// open StartupMacros with the Script Editor
			method = clazz.getMethod("openStartupMacros", "()V");
			method.insertBefore("String path = ij.IJ.getDirectory(\"macros\") + \"/StartupMacros.txt\";"
				+ "if (ij.IJ.runPlugIn(\"fiji.scripting.Script_Editor\", path) != null)"
				+ "  return;");

			clazz.toClass();

			if (!isImageJA) {
				// Class ij.plugin.frame.Recorder
				clazz = pool.get("ij.plugin.frame.Recorder");

				// create new macro in the Script Editor
				method = clazz.getMethod("createMacro", "()V");
				stripOutEditor(method.getMethodInfo());
				method.instrument(new ExprEditor() {
					@Override
					public void edit(MethodCall call) throws CannotCompileException {
						if (call.getMethodName().equals("createMacro"))
							call.replace("if ($1.endsWith(\".txt\"))"
								+ "  $1 = $1.substring($1.length() - 3) + \"ijm\";"
								+ "if (!fiji.FijiTools.openEditor($1, $2)) {"
								+ "  ed.createMacro($1, $2);"
								+ "}");
					}
				});
				// create new plugin in the Script Editor
				method = clazz.getMethod("createPlugin", "(Ljava/lang/String;Ljava/lang/String;)V");
				method.instrument(new ExprEditor() {
					@Override
					public void edit(MethodCall call) throws CannotCompileException {
						if (call.getMethodName().equals("runPlugIn"))
							call.replace("$_ = null;"
								+ "new ij.plugin.NewPlugin().createPlugin(name, ij.plugin.NewPlugin.PLUGIN, $2);"
								+ "return;");
					}
				});

				clazz.toClass();

				// Class ij.plugin.NewPlugin
				clazz = pool.get("ij.plugin.NewPlugin");

				// open new plugin in Script Editor
				method = clazz.getMethod("createMacro", "(Ljava/lang/String;)V");
				stripOutEditor(method.getMethodInfo());
				method.instrument(new ExprEditor() {

					@Override
					public void edit(MethodCall call) throws CannotCompileException {
						if (call.getMethodName().equals("create"))
							call.replace("if ($1.endsWith(\".txt\"))"
								+ "  $1 = $1.substring($1.length() - 3) + \"ijm\";"
								+ "if (!fiji.FijiTools.openEditor($1, $2)) {"
								+ "  int options = (monospaced ? ij.plugin.frame.Editor.MONOSPACED : 0) |"
								+ "    (menuBar ? ij.plugin.frame.Editor.MENU_BAR : 0);"
								+ "  ed = new ij.plugin.frame.Editor(rows, columns, 0, options);"
								+ "  ed.create($1, $2);"
								+ "}");
					}
				});
				// open new plugin in Script Editor
				method = clazz.getMethod("createPlugin", "(Ljava/lang/String;ILjava/lang/String;)V");
				stripOutEditor(method.getMethodInfo());
				method.instrument(new ExprEditor() {
					@Override
					public void edit(MethodCall call) throws CannotCompileException {
						if (call.getMethodName().equals("create"))
							call.replace("if (!fiji.FijiTools.openEditor($1, $2)) {"
								+ "  int options = (monospaced ? ij.plugin.frame.Editor.MONOSPACED : 0) |"
								+ "    (menuBar ? ij.plugin.frame.Editor.MENU_BAR : 0);"
								+ "  ed = new ij.plugin.frame.Editor(rows, columns, 0, options);"
								+ "  ed.create($1, $2);"
								+ "}");
					}

				});

				clazz.toClass();
			}

			// Class ij.WindowManager
			clazz = pool.get("ij.WindowManager");

			method = clazz.getMethod("addWindow", "(Ljava/awt/Frame;)V");
			method.insertBefore("if ($1 != null) {"
				+ "  java.net.URL url = fiji.Main.class.getResource(\"/icon.png\");"
				+ "  if (url != null) {"
				+ "    java.awt.Image img = $1.createImage((java.awt.image.ImageProducer)url.getContent());"
				+ "    if (img != null)"
				+ "      $1.setIconImage(img);"
				+ "  }"
				+ "}");

			clazz.toClass();

			// Class ij.macro.Functions
			clazz = pool.get("ij.macro.Functions");

			method = clazz.getMethod("call", "()Ljava/lang/String;");
			method.instrument(new ExprEditor() {
				@Override
				public void edit(Handler handler) throws CannotCompileException {
					try {
						if (handler.getType().getName().equals("java.lang.reflect.InvocationTargetException"))
							handler.insertBefore("ij.IJ.handleException($1);"
								+ "return null;");
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});
			handleHTTPS(clazz.getMethod("exec", "()Ljava/lang/String;"));

			clazz.toClass();

			// Make ij.Command a superclass of fiji.command.Command
			clazz = pool.getAndRename("fiji.command.Command", "ij.Command");
			clazz.toClass();
			clazz = pool.makeClass("fiji.command.Command", clazz);
			clazz.addConstructor(CtNewConstructor.make("public Command(String string) {"
				+ "  super(string);"
				+ "}", clazz));
			clazz.addMethod(CtNewMethod.make("public void notify(fiji.command.CommandListenerPlus listener, int action) {"
				+ "  listener.stateChanged(this, action);"
				+ "}", clazz));

			clazz.toClass();

			// Make ij.CommandListenerPlus a subinterface of fiji.command.CommandListenerPlus
			clazz = pool.makeInterface("ij.CommandListenerPlus", pool.get("fiji.command.CommandListenerPlus"));

			clazz.toClass();

			// Class ij.Executer
			clazz = pool.get("ij.Executer");

			// handle CommandListenerPlus instances
			field = new CtField(pool.get("fiji.command.Command"), "cmd", clazz);
			clazz.addField(field);
			method = clazz.getMethod("run", "()V");
			method.instrument(new ExprEditor() {
				@Override
				public void edit(MethodCall call) throws CannotCompileException {
					String name = call.getMethodName();
					if (name.equals("commandExecuting"))
						call.replace("$_ = $0.commandExecuting($1);"
							+ "if (cmd == null)"
							+ "  cmd = new fiji.command.Command($1);"
							+ "if ($0 instanceof fiji.command.CommandListenerPlus) {"
							+ "  cmd.notify((fiji.command.CommandListenerPlus)$0,"
							+ "     fiji.command.CommandListenerPlus.CMD_REQUESTED);"
							+ "  if (cmd.isConsumed())"
							+ "    return;"
							+ "}");
					else if (name.equals("runCommand"))
						call.replace("cmd.runCommand(listeners);");
				}

				@Override
				public void edit(Handler handler) throws CannotCompileException {
					try {
						CtClass type = handler.getType();
						if (type == null)
							return;
						if (type.getName().equals("java.lang.Throwable"))
							handler.insertBefore("if ($1 instanceof RuntimeException && ij.Macro.MACRO_CANCELED.equals($1.getMessage()))"
								+ "  cmd.notify(listeners, fiji.command.CommandListenerPlus.CMD_CANCELED);"
								+ "else"
								+ " cmd.notify(listeners, fiji.command.CommandListenerPlus.CMD_ERROR);");
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			});

			clazz.toClass();

			// handle mighty mouse (at least on old Linux, Java mistakes the horizontal wheel for a popup trigger)
			for (String name : new String[] { "ij.gui.ImageCanvas", "ij.plugin.frame.RoiManager", "ij.text.TextPanel" }) {
				clazz = pool.get(name);
				handleMousePressed(clazz);
				clazz.toClass();
			}

			// handle https:// in addition to http://
			clazz = pool.get("ij.io.PluginInstaller");
			handleHTTPS(clazz.getMethod("install", "(Ljava/lang/String;)Z"));
			clazz.toString();

			clazz = pool.get("ij.plugin.ListVirtualStack");
			handleHTTPS(clazz.getMethod("run", "(Ljava/lang/String;)V"));
			clazz.toString();

			// Add back the "Convert to 8-bit Grayscale" checkbox to Import>Image Sequence
			clazz = pool.get("ij.plugin.FolderOpener");
			if (!hasField(clazz, "convertToGrayscale")) {
				field = new CtField(CtClass.booleanType, "convertToGrayscale", clazz);
				clazz.addField(field);
				method = clazz.getMethod("run", "(Ljava/lang/String;)V");
				method.instrument(new ExprEditor() {
					protected int openImageCount;

					@Override
					public void edit(MethodCall call) throws CannotCompileException {
						if (call.getMethodName().equals("openImage") && openImageCount++ == 1)
							call.replace("$_ = $0.openImage($1, $2);"
								+ "if (convertToGrayscale)"
								+ "  ij.IJ.run($_, \"8-bit\", \"\");");
					}
				});
				method = clazz.getMethod("showDialog", "(Lij/ImagePlus;[Ljava/lang/String;)Z");
				method.instrument(new ExprEditor() {
					protected int addCheckboxCount, getNextBooleanCount;

					@Override
					public void edit(MethodCall call) throws CannotCompileException {
						String name = call.getMethodName();
						if (name.equals("addCheckbox") && addCheckboxCount++ == 0)
							call.replace("$0.addCheckbox(\"Convert to 8-bit Grayscale\", convertToGrayscale);"
								+ "$0.addCheckbox($1, $2);");
						else if (name.equals("getNextBoolean") && getNextBooleanCount++ == 0)
							call.replace("convertToGrayscale = $0.getNextBoolean();"
								+ "$_ = $0.getNextBoolean();"
								+ "if (convertToGrayscale && $_) {"
								+ "  ij.IJ.error(\"Cannot convert to grayscale and RGB at the same time.\");"
								+ "  return false;"
								+ "}");
					}
				});

				clazz.toClass();
			}

			// If there is a macros/StartupMacros.fiji, but no macros/StartupMacros.txt, execute that
			try {
				clazz = get("ij.Menus");
				File macrosDirectory = new File(FijiTools.getFijiDir(), "macros");
				File startupMacrosFile = new File(macrosDirectory, "StartupMacros.fiji");
				if (startupMacrosFile.exists() &&
						!new File(macrosDirectory, "StartupMacros.txt").exists() &&
						!new File(macrosDirectory, "StartupMacros.ijm").exists()) {
					method = clazz.getMethod("installStartupMacroSet", "()V");
					final String startupMacrosPath = startupMacrosFile.getPath().replace("\\", "\\\\").replace("\"", "\\\"");
					method.instrument(new ExprEditor() {
						@Override
						public void edit(MethodCall call) throws CannotCompileException {
							if (call.getMethodName().equals("installFromIJJar"))
								call.replace("$0.installFile(\"" + startupMacrosPath + "\");"
									+ "nMacros += $0.getMacroCount();");
						}
					});
				}
				clazz.toClass();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
		} catch (NotFoundException e) {
			e.printStackTrace();
		} catch (CannotCompileException e) {
			System.err.println(e.getMessage() + "\n" + e.getReason());
			e.printStackTrace();
			Throwable cause = e.getCause();
			if (cause != null)
				cause.printStackTrace();
		}
	}

	/**
	 * Replace the application name in the given method in the given parameter to the given constructor call
	 */
	public void replaceAppNameInNew(final CtMethod method, final String name, final int parameter, final int parameterCount) throws CannotCompileException {
		final String replace = getReplacement(parameter, parameterCount);
		method.instrument(new ExprEditor() {
			@Override
			public void edit(NewExpr expr) throws CannotCompileException {
				if (expr.getClassName().equals(name))
					expr.replace("$_ = new " + name + replace + ";");
			}
		});
	}

	/**
	 * Replace the application name in the given method in the given parameter to the given method call
	 */
	public void replaceAppNameInCall(final CtMethod method, final String name, final int parameter, final int parameterCount) throws CannotCompileException {
		final String replace = getReplacement(parameter, parameterCount);
		method.instrument(new ExprEditor() {
			@Override
			public void edit(MethodCall call) throws CannotCompileException {
				if (call.getMethodName().equals(name))
					call.replace("$0." + name + replace + ";");
			}
		});
	}

	private String getReplacement(int parameter, int parameterCount) {
		final StringBuilder builder = new StringBuilder();
		builder.append("(");
		for (int i = 1; i <= parameterCount; i++) {
			if (i > 1)
				builder.append(", ");
			builder.append("$").append(i);
			if (i == parameter)
				builder.append(replaceAppName);
		}
		builder.append(")");
		return builder.toString();
	}

	private void stripOutEditor(MethodInfo info) throws CannotCompileException {
		ConstPool constPool = info.getConstPool();
		CodeIterator iterator = info.getCodeAttribute().iterator();
	        while (iterator.hasNext()) try {
	                int pos = iterator.next();
			int c = iterator.byteAt(pos);
			if (c == Opcode.LDC) {
				int index = iterator.byteAt(pos + 1);
				if (constPool.getTag(index) == ConstPool.CONST_String &&
						constPool.getStringInfo(index).equals("ij.plugin.frame.Editor") &&
						iterator.byteAt(pos + 2) == Opcode.LDC &&
						iterator.byteAt(pos + 4) == Opcode.INVOKESTATIC &&
						iterator.byteAt(pos + 7) == Opcode.CHECKCAST &&
						iterator.byteAt(pos + 10) == Opcode.PUTFIELD &&
						iterator.byteAt(pos + 13) == Opcode.ALOAD_0 &&
						iterator.byteAt(pos + 14) == Opcode.GETFIELD &&
						iterator.byteAt(pos + 17) == Opcode.IFNONNULL &&
						iterator.byteAt(pos + 20) == Opcode.RETURN)
					for (int i = 0; i < 21; i++)
						iterator.writeByte(Opcode.NOP, pos + i);
			}
		}
		catch (BadBytecode e) {
			throw new CannotCompileException(e);
		}
	}

	private void handleMousePressed(CtClass clazz) throws CannotCompileException, NotFoundException {
		// Work around a bug where the horizontal scroll wheel of the mighty mouse is mistaken for a popup trigger
		ExprEditor editor = new ExprEditor() {
			@Override
			public void edit(MethodCall call) throws CannotCompileException {
				if (call.getMethodName().equals("isPopupTrigger"))
					call.replace("$_ = $0.isPopupTrigger() && $0.getButton() != 0;");
			}
		};
		CtMethod method = clazz.getMethod("mousePressed", "(Ljava/awt/event/MouseEvent;)V");
		method.instrument(editor);
		try {
			method = clazz.getMethod("mouseDragged", "(Ljava/awt/event/MouseEvent;)V");
			method.instrument(editor);
		} catch (NotFoundException e) { /* ignore */ }
	}

	private void handleHTTPS(final CtMethod method) throws CannotCompileException {
		method.instrument(new ExprEditor() {
			@Override
			public void edit(MethodCall call) throws CannotCompileException {
				try {
					if (call.getMethodName().equals("startsWith") &&
							"http://".equals(getLatestArg(call, 0)))
						call.replace("$_ = $0.startsWith($1) || $0.startsWith(\"https://\");");
				} catch (BadBytecode e) {
					e.printStackTrace();
				} catch (NotFoundException e) {
					e.printStackTrace();
				}
			}
		});
	}

	private String getLatestArg(MethodCall call, int skip) throws BadBytecode, NotFoundException {
		int[] indices = new int[skip + 1];
		int counter = 0;

		MethodInfo info = ((CtMethod)call.where()).getMethodInfo();
		CodeIterator iterator = info.getCodeAttribute().iterator();
		int currentPos = call.indexOfBytecode();
		while (iterator.hasNext()) {
			int pos = iterator.next();
			if (pos >= currentPos)
				break;
			switch (iterator.byteAt(pos)) {
			case Opcode.LDC:
				indices[(counter++) % indices.length] = iterator.byteAt(pos + 1);
				break;
			case Opcode.LDC_W:
				indices[(counter++) % indices.length] = iterator.u16bitAt(pos + 1);
				break;
			}
		}
		if (counter < skip)
			return null;
		return info.getConstPool().getStringInfo(indices[(indices.length + counter - skip) % indices.length]);
	}

	private boolean hasField(CtClass clazz, String name) {
		try {
			return clazz.getField(name) != null;
		} catch (NotFoundException e) {
			return false;
		}
	}
}