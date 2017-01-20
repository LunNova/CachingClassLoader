package net.minecraft.launchwrapper.nallar.cachingclassloader;

import java.util.function.*;

public interface CachingClassLoader {
	/**
	 * Removes cached failed classes which satisfy the passed function
	 */
	void clearFailures(Function<String, Boolean> predicate);

	/**
	 * Prevents logging an error if a class is loaded both by LaunchClassLoader and by the parent classloader.
	 * Generally this is a bug and can cause confusion
	 */
	void addDoubleLoadExclusion(String toExclude);
}
