package net.minecraft.launchwrapper.nallar.cachingclassloader;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.val;
import net.minecraft.launchwrapper.LogWrapper;
import org.apache.logging.log4j.Level;

import java.io.*;
import java.nio.file.*;
import java.util.*;

@AllArgsConstructor
@Data
class CacheState implements Serializable {
	List<FileState> states;

	CacheState(File directory) {
		states = search(new ArrayList<>(), directory, 0);
	}

	@SneakyThrows
	private static List<FileState> search(List<FileState> fileStates, File directory, int depth) {
		if (depth > 20) {
			throw new IllegalArgumentException(directory + " depth too high: " + depth);
		}

		val files = directory.listFiles();
		if (files == null)
			throw new IOException(directory + " is not a directory");

		for (val f : files) {
			val name = f.getName();
			val lName = name.toLowerCase();
			if (f.isDirectory()) {
				if (depth == 0 && !("mods".equals(lName) || "libraries".equals(lName)))
					continue;
				search(fileStates, f, depth + 1);
				continue;
			}

			if (lName.endsWith(".jar") || lName.endsWith(".jlib") || lName.endsWith(".zip"))
				fileStates.add(new FileState(f));
		}

		fileStates.sort(Comparator.comparing(a -> a.path));

		return fileStates;
	}

	static CacheState readFromFile(File file) {
		if (!file.exists())
			return null;

		try {
			try (val is = new ObjectInputStream(new FileInputStream(file))) {
				return (CacheState) is.readObject();
			}
		} catch (Throwable t) {
			LogWrapper.log(Level.WARN, t, "Error occured trying to read cache state from " + file);
			return null;
		}
	}

	void writeToFile(File file) {
		val temp = new File(file.getParentFile(), file.getName() + ".temp");
		try {
			try (val os = new ObjectOutputStream(new FileOutputStream(temp))) {
				os.writeObject(this);
			}
			Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Throwable t) {
			LogWrapper.log(Level.WARN, t, "Error occured trying to write cache state to " + file);
		}
	}

	@Data
	@AllArgsConstructor
	private static class FileState implements Serializable {
		private String path;
		private long time;
		private long size;

		@SneakyThrows
		FileState(File f) {
			path = f.getCanonicalPath();
			time = f.lastModified();
			size = f.length();
		}
	}
}
