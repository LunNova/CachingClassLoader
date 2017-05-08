package net.minecraft.launchwrapper.nallar.cachingclassloader;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

public class Main {
	public static void main(String[] args) {
		ClassLoader classLoader = Main.class.getClassLoader();
		String loc = null;
		ArrayList<String> argsList = new ArrayList<>(Arrays.asList(args));
		for (Iterator<String> i$ = argsList.iterator(); i$.hasNext(); ) {
			String arg = i$.next();
			final String serverJarArgument = "--serverjar=";
			if (arg.toLowerCase().startsWith(serverJarArgument)) {
				loc = arg.substring(serverJarArgument.length());
				i$.remove();
				break;
			}
		}
		args = argsList.toArray(new String[argsList.size()]);
		if (loc == null) {
			loc = System.getProperty("serverJar");
		}
		loc = loc == null ? null : loc.trim();
		addLibraries((URLClassLoader) classLoader, loc);

		try {
			Class<?> launchwrapper = Class.forName("net.minecraft.launchwrapper.Launch", true, classLoader);
			System.out.println(String.valueOf(launchwrapper.getClassLoader()));
			Class.forName("org.objectweb.asm.Type", true, classLoader);
			Method main = launchwrapper.getMethod("main", String[].class);
			String[] allArgs = new String[args.length + 2];
			allArgs[0] = "--tweakClass";
			allArgs[1] = "net.minecraftforge.fml.common.launcher.FMLServerTweaker";
			System.arraycopy(args, 0, allArgs, 2, args.length);
			main.invoke(null, (Object) allArgs);
		} catch (ClassNotFoundException e) {
			System.err.println(e.toString());
			System.exit(1);
		} catch (Throwable t) {
			System.err.println("A problem occurred running the Server launcher.");
			t.printStackTrace(System.err);
			System.exit(1);
		}

	}

	private static void addLibraries(URLClassLoader classLoader, String loc) {
		if (loc == null) {
			System.err.println("You have not specified a server jar");
			System.err.println("Please add --serverJar=<minecraft forge jar name here> at the end of your java arguments.");
			System.err.println("Example: java -jar cachingClassLoader.jar --serverJar=minecraft_forge.jar");
			System.exit(1);
		}
		File locFile = new File(loc);
		try {
			locFile = locFile.getCanonicalFile();
		} catch (IOException e) {
			e.printStackTrace(System.err);
		}
		if (locFile.exists()) {
			System.out.println("Adding server jar: " + loc + " @ " + locFile + " to libraries.");
			addPathToClassLoader(locFile, classLoader);
		} else {
			System.err.println("Could not find specified server jar: " + loc + " @ " + locFile);
			System.exit(1);
		}
	}

	private static Method getAddURLMethod() {
		Method method;
		try {
			method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
		method.setAccessible(true);
		return method;
	}

	private static void addPathToClassLoader(File path, URLClassLoader classLoader) {
		try {
			URL u = path.toURI().toURL();
			System.out.println("Added " + u + " to " + classLoader);
			getAddURLMethod().invoke(classLoader, u);
		} catch (Throwable t) {
			throw new RuntimeException(t);
		}
	}
}
