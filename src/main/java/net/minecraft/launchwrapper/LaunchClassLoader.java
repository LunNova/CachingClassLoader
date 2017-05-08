package net.minecraft.launchwrapper;

import lombok.SneakyThrows;
import lombok.val;
import net.minecraft.launchwrapper.nallar.cachingclassloader.Cache;
import net.minecraft.launchwrapper.nallar.cachingclassloader.CachingClassLoader;
import net.minecraft.launchwrapper.nallar.cachingclassloader.PropertyLoader;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.jar.*;
import java.util.jar.Attributes.*;

/**
 * Note: While this still has the same name/package as the version from launchwrapper, its internals are so different that
 * maintaining it as a diff against the original LaunchClassLoader results in a massive mess
 * <p>
 * Replacement for LCL which caches transformed classes
 * Public API must match that of
 * https://github.com/Mojang/LegacyLauncher/blob/master/src/main/java/net/minecraft/launchwrapper/LaunchClassLoader.java
 * <p>
 * Private API also mostly matches the original LaunchClassLoader as some mods such as FoamFix and Sponge use reflection
 * to interact with them.
 * <p>
 * Breaking changes:
 * private field packageManifests has been removed
 * private field cachedClasses is empty on cached starts to workaround a sponge behaviour
 * private field negativeResourceCache has been removed (consolidated to single map for performance)
 * private field invalidClasses has been removed (consolidated to single map for performance)
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class LaunchClassLoader extends URLClassLoader implements CachingClassLoader {
	public static final int BUFFER_SIZE = 1 << 16; // 64kb
	public static final String CACHING_CLASS_LOADER_LOADED = "cachingClassLoader.loaded";
	private static final Set<String> LAUNCH_TARGETS = new HashSet<>(Arrays.asList("net.minecraft.server.MinecraftServer", "net.minecraft.client.Minecraft"));
	private static final String[] RESERVED_NAMES = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};
	private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("legacy.debugClassLoading", "false"));
	private static final boolean LOG_PACKAGE_TAMPERING = Boolean.parseBoolean(System.getProperty("cachingClassLoader.logPackageTampering", "false"));
	private static final Method classLoaderFindLoadedMethod = getClassLoaderFindLoadedMethod();
	private static final ThreadLocal<byte[]> loadBuffer = ThreadLocal.withInitial(() -> new byte[BUFFER_SIZE]);
	/**
	 * Used in maps to indicate that we tried to retrieve the class, but failed
	 */
	@SuppressWarnings("MismatchedReadAndWriteOfArray")
	private static final byte[] BYTE_CACHE_ERRORED = new byte[0];
	private static final Class<?> CLASS_CACHE_ERRORED = Object.class;

	static {
		PropertyLoader.loadPropertiesFromFile(new File("./config/CachingClassLoader.cfg"));
	}

	/**
	 * Parent classloader - typically sun.misc.Launcher$AppClassLoader
	 * Used to load classes which are excluded from this transformer
	 */
	private final ClassLoader parent = getClass().getClassLoader();
	private final Cache cache = new Cache(new File("CachingClassLoader"));
	/**
	 * Used to prevent minecraft classes from being loaded before Launch has actually launched the game
	 */
	public boolean allowMinecraftClassLoading;
	private List<URL> sources;
	private List<IClassTransformer> transformers = new ArrayList<>();
	private Map<String, Class<?>> cachedClasses_ = new ConcurrentHashMap<>();
	//Vanilla one - kept normal on first starts, cached starts left empty, for sponge compat
	private Map<String, Class<?>> cachedClasses = (!PropertyLoader.enableSpongeWorkarounds() || cache.isFreshStart()) ? cachedClasses_ : Collections.emptyMap();
	private Set<String> classLoaderExceptions = new HashSet<>();
	private Set<String> doubleLoadExceptions = new HashSet<>();
	private Set<String> transformerExceptions = new HashSet<>();
	// Misleadingly, this is keyed by class name, not resource name
	// Kept that way for compat with standard LCL
	private Map<String, byte[]> resourceCache = new ConcurrentHashMap<>();
	private IClassNameTransformer renameTransformer;

	@SneakyThrows
	public LaunchClassLoader(URL[] sources) {
		super(sources, null);
		if (Objects.equals(System.getProperty(CACHING_CLASS_LOADER_LOADED), "true"))
			throw new Error("Only one LaunchClassLoader instance should exist");
		System.setProperty(CACHING_CLASS_LOADER_LOADED, "true");

		this.sources = new ArrayList<>(Arrays.asList(sources));

		// classloader exclusions
		addClassLoaderExclusion("argo.");
		addClassLoaderExclusion("com.google.");
		addClassLoaderExclusion("com.mojang.");
		addClassLoaderExclusion("java.");
		addClassLoaderExclusion("javax.");
		addClassLoaderExclusion("net.minecraft.launchwrapper.");
		addClassLoaderExclusion("org.apache.logging.");
		addClassLoaderExclusion("org.bouncycastle.");
		addClassLoaderExclusion("org.objectweb.asm.");
		addClassLoaderExclusion("org.lwjgl.");
		addClassLoaderExclusion("sun.");

		// transformer exclusions
		//addTransformerExclusion("com.google.");
		addTransformerExclusion("javassist.");
		addTransformerExclusion("kotlin.");
		addTransformerExclusion("net.minecraft.launchwrapper.injector.");

		// allow double loading without warning
		// TODO: fml.common.asm may actually be caused by a bug, investigate what loads them in earlier
		// A mod had a bug which required this to be classloaded in same classloader.
		//addDoubleLoadExclusion("com.google.gson.");
		addDoubleLoadExclusion("net.minecraftforge.fml.common.asm.transformers.");
		addDoubleLoadExclusion("net.minecraftforge.fml.common.asm.ASMTransformerWrapper");
		addDoubleLoadExclusion("net.minecraftforge.server.console.");

		Thread.currentThread().setContextClassLoader(this);
		// TODO: Better DEBUG_SAVE implementation which does per-transformer change dumping
		// ideally would also be able to decompile+diff using passed fernflower jar
	}

	private static Method getClassLoaderFindLoadedMethod() {
		try {
			Method m = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
			m.setAccessible(true);
			return m;
		} catch (NoSuchMethodException e) {
			LogWrapper.log(Level.ERROR, e, "");
			return null;
		}
	}

	public static byte[] readFully(InputStream stream) {
		try (val bos = new ByteArrayOutputStream(stream.available())) {
			int readBytes;
			val buffer = loadBuffer.get();

			while ((readBytes = stream.read(buffer, 0, buffer.length)) != -1)
				bos.write(buffer, 0, readBytes);

			return bos.toByteArray();
		} catch (Throwable t) {
			LogWrapper.log(Level.ERROR, t, "Unable to read class data from stream %s", stream);
			return BYTE_CACHE_ERRORED;
		}
	}

	/**
	 * Handles workaround for RESERVED_NAMES
	 *
	 * @param name name should be of form package.package.Name$Inner
	 * @return Path for resource for given class name
	 */
	private static String classNameToResourceName(String name) {
		if (name.length() < 8 && name.indexOf('.') == -1) {
			val upperCaseName = name.toUpperCase(Locale.ENGLISH);
			for (val reservedName : RESERVED_NAMES)
				if (upperCaseName.startsWith(reservedName)) {
					name = '_' + name;
					break;
				}
		}
		return name.replace('.', '/') + ".class";
	}

	private static boolean excluded(String name, Set<String> exceptions) {
		for (val exception : exceptions)
			if (name.startsWith(exception))
				return true;

		return false;
	}

	private static void addExclusion(Set<String> set, String toExclude) {
		if (toExclude == null || toExclude.isEmpty() || toExclude.contains("/"))
			throw new IllegalArgumentException("toExclude: '" + toExclude + "'");

		val iter = set.iterator();
		while (iter.hasNext()) {
			val exc = iter.next();
			if (exc.equals(toExclude))
				return;
			if (exc.startsWith(toExclude))
				iter.remove();
			if (toExclude.startsWith(exc))
				return;
		}
		set.add(toExclude);
	}

	private static boolean isSealed(final String path, final Manifest manifest) {
		val sealed = isSealed(path, manifest.getAttributes(path));
		return sealed == Boolean.TRUE || (sealed == null && isSealed(path, manifest.getMainAttributes()) == Boolean.TRUE);
	}

	private static Boolean isSealed(final String path, final Attributes attributes) {
		if (attributes == null)
			return null;
		return "true".equalsIgnoreCase(attributes.getValue(Name.SEALED));
	}

	public void registerTransformer(String transformerClassName) {
		try {
			val transformer = (IClassTransformer) loadClass(transformerClassName).newInstance();
			transformers.add(transformer);
			if (transformer instanceof IClassNameTransformer)
				if (renameTransformer == null)
					renameTransformer = (IClassNameTransformer) transformer;
				else
					LogWrapper.severe("Can't set renameTransformer to %s as it is already set to %s", transformerClassName, renameTransformer.getClass().getName());
		} catch (Exception e) {
			LogWrapper.log(Level.ERROR, e, "A critical problem occurred registering the ASM transformer class %s", transformerClassName);
		}
	}

	@Override
	public Class<?> findClass(final String name) throws ClassNotFoundException {
		val cached = cachedClasses_.get(name);
		if (cached == CLASS_CACHE_ERRORED)
			throw new ClassNotFoundException(name);
		if (cached != null)
			return cached;

		if (excluded(name, classLoaderExceptions))
			return parent.loadClass(name);

		Class alreadyLoaded = null;
		try {
			alreadyLoaded = (Class) classLoaderFindLoadedMethod.invoke(parent, name);
		} catch (Throwable t) {
			LogWrapper.log(Level.ERROR, t, "Failed to invoke classLoaderFindLoadedMethod for %s", name);
		}

		if (alreadyLoaded != null) {
			if (name.startsWith("net.minecraft.launchwrapper")) {
				val e = new InternalError("Already classloaded earlier: " + name);
				LogWrapper.log(Level.ERROR, e, "");
				throw e;
			}
			if (excluded(name, doubleLoadExceptions))
				LogWrapper.severe("Non-excluded class %s has already been loaded by the parent classloader. It should be excluded or should not have been already loaded.", name);
		}

		if (excluded(name, transformerExceptions))
			try {
				final Class<?> clazz = super.findClass(name);
				if (clazz == null)
					throw new ClassNotFoundException("null from super.findClass for " + name);
				cachedClasses_.put(name, clazz);
				return clazz;
			} catch (ClassNotFoundException e) {
				cachedClasses_.put(name, CLASS_CACHE_ERRORED);
				throw e;
			}

		try {
			val transformedName = transformName(name);
			val untransformedName = untransformName(name);
			if (!transformedName.equals(name))
				throw new Error("Asked for '" + name + "', but that should be called '" + transformedName + "'");

			boolean neverCache = false;
			if (!allowMinecraftClassLoading) {
				if (LAUNCH_TARGETS.contains(name)) {
					// We're launching now
					neverCache = PropertyLoader.enableSpongeWorkarounds();
					allowMinecraftClassLoading = true;
					cache.updateCacheState();
					LogWrapper.info("Detected launch target load %s", name);
				} else if (transformedName.startsWith("net.minecraft.crash")) {
					neverCache = PropertyLoader.enableSpongeWorkarounds();
				} else if (!untransformedName.equals(transformedName) && transformedName.startsWith("net.minecraft.")) {
					val e = new Error("Can not load " + transformedName + "/" + untransformedName + " as we are not ready to load minecraft classes");
					LogWrapper.log(Level.ERROR, e, "");
					throw e;
				}
			}

			val lastDot = untransformedName.lastIndexOf('.');
			val packageName = lastDot == -1 ? "" : untransformedName.substring(0, lastDot);
			val pkg = getPackage(packageName);

			val resourceName = classNameToResourceName(untransformedName);
			val resource = findResource(resourceName);

			CodeSigner[] signers = null;
			byte[] classBytes = null;
			if (lastDot > -1 && !untransformedName.startsWith("net.minecraft.")) {
				URLConnection urlConnection = resource == null ? null : resource.openConnection();
				if (urlConnection instanceof JarURLConnection) {
					val jarURLConnection = (JarURLConnection) urlConnection;
					val jarFile = jarURLConnection.getJarFile();

					if (jarFile != null && jarFile.getManifest() != null) {
						val manifest = jarFile.getManifest();
						val entry = jarFile.getJarEntry(resourceName);

						// signature verification only works if we've read the inputstream from the entry
						// so we must call getClassBytes here, even if a cached transformed class is available
						// see entry.getCodeSigners docs
						classBytes = getClassBytes(untransformedName, resource);
						signers = entry.getCodeSigners();
						if (pkg == null) {
							definePackage(packageName, manifest, jarURLConnection.getJarFileURL());
						} else if (LOG_PACKAGE_TAMPERING) {
							if (pkg.isSealed() && !pkg.isSealed(jarURLConnection.getJarFileURL())) {
								LogWrapper.severe("The jar file %s is trying to seal already secured path %s", jarFile.getName(), packageName);
							} else if (isSealed(packageName, manifest)) {
								LogWrapper.severe("The jar file %s has a security seal for path %s, but that path is defined and not secure", jarFile.getName(), packageName);
							}
						}
					}
				} else {
					if (pkg == null) {
						definePackage(packageName, null, null, null, null, null, null, null);
					} else if (LOG_PACKAGE_TAMPERING && pkg.isSealed()) {
						LogWrapper.severe("The URL %s is defining elements for sealed path %s", resource, packageName);
					}
				}
			}

			byte[] transformedClass = cache.getClassBytes(transformedName);
			val needsCached = transformedClass == null;
			if (needsCached || neverCache)
				transformedClass = runTransformers(untransformedName, transformedName, classBytes == null ? getClassBytes(untransformedName) : classBytes);

			if (transformedClass == null)
				throw new ClassNotFoundException(name + " could not be found to load");

			val clazz = defineClass(transformedName, transformedClass, 0, transformedClass.length, resource == null ? null : new CodeSource(resource, signers));

			cachedClasses_.put(transformedName, clazz);
			if (needsCached)
				cache.saveClassBytes(transformedName, transformedClass);

			return clazz;
		} catch (Throwable e) {
			cachedClasses_.put(name, CLASS_CACHE_ERRORED);
			if (DEBUG) {
				LogWrapper.log(Level.TRACE, e, "Exception encountered attempting classloading of %s", name);
				LogManager.getLogger("LaunchWrapper").log(Level.ERROR, String.format("Exception encountered attempting classloading of %s", e));
			}
			throw new ClassNotFoundException(name, e);
		}
	}

	private String untransformName(final String name) {
		return renameTransformer == null ? name : renameTransformer.unmapClassName(name);
	}

	private String transformName(final String name) {
		return renameTransformer == null ? name : renameTransformer.remapClassName(name);
	}

	@SneakyThrows
	private URLConnection findCodeSourceConnectionFor(final String name) {
		val resource = findResource(name);
		return resource == null ? null : resource.openConnection();
	}

	private byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
		for (final IClassTransformer transformer : transformers)
			basicClass = transformer.transform(name, transformedName, basicClass);

		return basicClass;
	}

	@Override
	public void addURL(final URL url) {
		super.addURL(url);
		sources.add(url);
	}

	public List<URL> getSources() {
		return sources;
	}

	public List<IClassTransformer> getTransformers() {
		return Collections.unmodifiableList(transformers);
	}

	public void addClassLoaderExclusion(final String toExclude) {
		addExclusion(classLoaderExceptions, toExclude);
	}

	public void addTransformerExclusion(final String toExclude) {
		addExclusion(transformerExceptions, toExclude);
	}

	public void addDoubleLoadExclusion(final String toExclude) {
		addExclusion(doubleLoadExceptions, toExclude);
	}

	public byte[] getClassBytes(String name) throws IOException {
		name = name.replace('/', '.');
		if (name.startsWith("java."))
			return null;

		return getClassBytes(name, null);
	}

	private byte[] getClassBytes(final String name, URL resource) throws IOException {
		byte[] cached = resourceCache.get(name);
		if (cached != null)
			return cached == BYTE_CACHE_ERRORED ? null : cached;

		if (resource == null)
			resource = findResource(classNameToResourceName(name));

		if (resource == null) {
			if (DEBUG) LogWrapper.finest("Failed to find class resource %s", name);
			resourceCache.put(name, BYTE_CACHE_ERRORED);
			return null;
		}

		try (val classStream = resource.openStream()) {
			if (DEBUG) LogWrapper.finest("Loading class %s from resource %s", name, resource.toString());
			val data = readFully(classStream);
			resourceCache.put(name, data);
			return data;
		}
	}

	public void clearNegativeEntries(Set<String> entriesToClear) {
		for (String entry : entriesToClear) {
			entry = entry.replace('/', '.');
			if (resourceCache.get(entry) == BYTE_CACHE_ERRORED)
				resourceCache.remove(entry);
			if (cachedClasses_.get(entry) == CLASS_CACHE_ERRORED)
				cachedClasses_.remove(entry);
		}
	}

	@Override
	public void clearFailures(Function<String, Boolean> predicate) {
		resourceCache.entrySet().removeIf((it) -> it.getValue() == BYTE_CACHE_ERRORED && predicate.apply(it.getKey()));
		cachedClasses_.entrySet().removeIf((it) -> it.getValue() == CLASS_CACHE_ERRORED && predicate.apply(it.getKey()));
	}
}
