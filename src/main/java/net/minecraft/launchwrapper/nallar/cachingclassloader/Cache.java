package net.minecraft.launchwrapper.nallar.cachingclassloader;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.val;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.launchwrapper.LogWrapper;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class Cache {
	private static final String nameFormat = "transformed-classes-%d.temp";
	private final File dir;
	private final boolean enabled;
	private Map<String, Callable<InputStream>> classes = new HashMap<>();
	private ZipOutputStream nextCache;
	private int cacheZipCount = 0;
	private int cachedClasses = 0;
	@Getter
	private boolean isFreshStart;

	@SneakyThrows
	public Cache(File dir) {
		if (!dir.isDirectory() && !dir.mkdirs())
			throw new IOException("Can't create directory: " + dir.getCanonicalPath());

		dir = dir.getCanonicalFile();
		this.dir = dir;

		enabled = PropertyLoader.enableCaching();
		if (!enabled)
			return;

		val cacheStateFile = new File(dir, "cachestate.obj");
		val oldState = CacheState.readFromFile(cacheStateFile);
		val newState = new CacheState(dir.getParentFile());

		val removeOld = isFreshStart = oldState == null || !oldState.equals(newState);
		if (removeOld && cacheStateFile.exists() && !cacheStateFile.delete())
			throw new IOException("Failed to delete cache state at " + cacheStateFile);

		List<ZipFile> cacheZips = new ArrayList<>();
		val files = dir.listFiles();
		if (files != null)
			for (val f : files) {
				val name = f.getName().toLowerCase();
				if (name.endsWith(".jar")) {
					if (removeOld) {
						if (!f.delete()) {
							LogWrapper.severe("Unable to remove old cached classes %s", f);
						}
					} else {
						cacheZips.add(new ZipFile(f));
						cacheZipCount++;
					}
				} else if (name.endsWith(".tempjar") && !f.delete()) {
					throw new IOException("Failed to delete old temporary cached classes jar: " + f);
				}
			}

		for (val zipFile : cacheZips)
			for (val zipEntry : Collections.list(zipFile.entries()))
				classes.put(zipEntry.getName(), () -> zipFile.getInputStream(zipEntry));

		if (removeOld) {
			newState.writeToFile(cacheStateFile);
			LogWrapper.info("Cleared cached transformed classes as cache states did not match.\nBefore: " + oldState + "\nAfter: " + newState);
		}

		LogWrapper.info("Loaded " + classes.size() + " cached transformed classes from " + cacheZipCount + " jar" + (cacheZipCount == 1 ? "" : "s") + ".");

		Runtime.getRuntime().addShutdownHook(new Thread(this::closeCurrentCache, "CachingClassLoader saver"));
	}

	public void updateCacheState() {
		val cacheStateFile = new File(dir, "cachestate.obj");
		val oldState = CacheState.readFromFile(cacheStateFile);
		val newState = new CacheState(dir.getParentFile());

		if (!newState.equals(oldState))
			newState.writeToFile(cacheStateFile);
	}

	@SneakyThrows
	public byte[] getClassBytes(String name) {
		if (!enabled)
			return null;

		val isCallable = classes.get(name);
		if (isCallable == null)
			return null;

		try (val is = isCallable.call()) {
			return LaunchClassLoader.readFully(is);
		}
	}

	@SneakyThrows
	public synchronized void saveClassBytes(String name, byte[] contents) {
		if (!enabled)
			return;

		if (cachedClasses > 5000) {
			closeCurrentCache();
		}

		if (nextCache == null)
			nextCache = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(nextCacheLocation())));

		val entry = new ZipEntry(name);
		try {
			nextCache.putNextEntry(entry);
			nextCache.write(contents);
		} finally {
			nextCache.closeEntry();
			cachedClasses++;
		}
	}

	@SneakyThrows
	private synchronized void closeCurrentCache() {
		if (nextCache == null)
			return;

		nextCache.close();
		cachedClasses = 0;
		nextCache = null;
		val current = nextCacheLocation();
		val dest = new File(current.getParentFile(), current.getName().replace(".temp", ".jar"));
		if (!current.renameTo(dest))
			LogWrapper.severe("Unable to rename complete class cached from '%s' to '%s'", current, dest);
		cacheZipCount++;
	}

	private File nextCacheLocation() {
		return new File(dir, String.format(nameFormat, cacheZipCount));
	}
}
