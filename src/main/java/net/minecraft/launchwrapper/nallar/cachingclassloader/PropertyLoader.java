package net.minecraft.launchwrapper.nallar.cachingclassloader;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.val;

import java.io.*;
import java.nio.file.*;

@UtilityClass
public class PropertyLoader {
	private static final String PREFIX = "cachingClassLoader.";

	public static boolean enableSpongeWorkarounds() {
		return getBoolean("enableSpongeWorkarounds", true);
	}

	static boolean enableCaching() {
		return getBoolean("enableCaching", true);
	}

	static boolean onlyInvalidateCacheUsingCacheKey() {
		return getBoolean("onlyInvalidateCacheUsingCacheKey", false);
	}

	static String getCacheKey() {
		val key = System.getProperty(PREFIX + "cacheKeyOverride");
		return key == null || key.isEmpty() ? "none" : key;
	}

	private static boolean getBoolean(String key, boolean def) {
		return Boolean.parseBoolean(System.getProperty(PREFIX + key, String.valueOf(def)));
	}

	@SneakyThrows
	public static void loadPropertiesFromFile(File file) {
		if (!file.exists()) {
			Files.write(file.toPath(), (
				"enableCaching=true\r\n" +
					"serverJar=\r\n" +
					"cacheKeyOverride=\r\n" +
					"onlyInvalidateCacheUsingCacheKey=false\r\n" +
					"enableSpongeWorkarounds=true\r\n"
			).getBytes());
		}
		String data = new String(Files.readAllBytes(file.toPath()));
		data = data.replace("\r\n", "\n");
		for (String line : data.split("\n")) {
			String[] parts = line.split("=");
			if (parts.length == 2) {
				String value = parts[1];
				String key = parts[0];
				if (!key.isEmpty() && !value.isEmpty()) {
					System.setProperty(PREFIX + key, value);
				}
			}
		}
	}
}
