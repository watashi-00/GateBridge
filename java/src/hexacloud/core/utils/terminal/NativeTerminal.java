package hexacloud.core.utils.terminal;

import java.io.File;
import java.io.FileWriter;
import hexacloud.core.utils.concurrent.ThreadManager;

public class NativeTerminal {
    private static boolean loaded = false;
    private static boolean sttyRawModeActive = false;

    static {
        // Try custom path from System Property or Env Var first
        String customPath = System.getProperty("gatebridge.jni.path");
        if (customPath == null) {
            customPath = System.getenv("GATEBRIDGE_JNI_PATH");
        }
        if (customPath != null && !customPath.trim().isEmpty()) {
            try {
                File file = new File(customPath.trim());
                if (file.exists()) {
                    System.load(file.getAbsolutePath());
                    loaded = true;
                }
            } catch (Throwable t) {
                System.err.println("Warning: Failed to load JNI library from custom path: " + customPath + ". Error: " + t.getMessage());
            }
        }

        // Try loading from packaged JAR resource next if not loaded
        if (!loaded) {
            try {
            String osName = System.getProperty("os.name").toLowerCase();
            String libName;
            if (osName.contains("win")) {
                libName = "hexaterminal.dll";
            } else if (osName.contains("mac")) {
                libName = "libhexaterminal.dylib";
            } else {
                libName = "libhexaterminal.so";
            }
            
            try (java.io.InputStream in = NativeTerminal.class.getResourceAsStream("/native/" + libName)) {
                if (in != null) {
                    File tempFile = File.createTempFile("libhexaterminal", libName.substring(libName.lastIndexOf('.')));
                    tempFile.deleteOnExit();
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    System.load(tempFile.getAbsolutePath());
                    loaded = true;
                }
            }
        } catch (Throwable t) {
            // Ignore and fallback
        }
    }

    if (!loaded) {
            String[] possiblePaths = {
                "libhexaterminal.so",
                "java/libhexaterminal.so",
                "/tmp/libhexaterminal.so",
                "libhexaterminal.dylib",
                "java/libhexaterminal.dylib",
                "/tmp/libhexaterminal.dylib",
                "hexaterminal.dll",
                "java/hexaterminal.dll"
            };
            for (String path : possiblePaths) {
                try {
                    File file = new File(path);
                    if (file.exists()) {
                        System.load(file.getAbsolutePath());
                        loaded = true;
                        break;
                    }
                } catch (Throwable t) {
                    // Try next path
                }
            }
        }

        if (!loaded) {
            try {
                System.loadLibrary("hexaterminal");
                loaded = true;
            } catch (Throwable t) {
                System.err.println("Warning: libhexaterminal could not be loaded. Native JNI raw input disabled. Falling back to Java console emulation.");
            }
        }
    }

    private static native void initTerminal0();
    private static native void resetTerminal0();
    private static native void clearScreen0();
    private static native void printAt0(int x, int y, String text);
    private static native int readKey0();
    private static native boolean saveConfig0(String filepath, String content);
    private static native int getTerminalWidth0();
    private static native int getTerminalHeight0();

    public static boolean loadJni(String path) {
        if (loaded) return true;
        try {
            File file = new File(path);
            if (file.exists()) {
                System.load(file.getAbsolutePath());
                loaded = true;
                return true;
            }
        } catch (Throwable t) {
            System.err.println("Warning: Failed to load JNI library from: " + path + ". Error: " + t.getMessage());
        }
        return false;
    }

    public static synchronized void initTerminal() {
        if (loaded) {
            try {
                initTerminal0();
                return;
            } catch (UnsatisfiedLinkError e) {
                // Fallback
            }
        }
        // Fallback Unix stty raw mode
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("linux") || osName.contains("mac") || osName.contains("nix") || osName.contains("nux")) {
                new ProcessBuilder("sh", "-c", "stty raw -echo < /dev/tty").start().waitFor();
                sttyRawModeActive = true;
                // Clear screen and hide cursor using ANSI escape code
                System.out.print("\033[2J\033[H\033[3J\033[?25l");
                System.out.flush();
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    public static synchronized void resetTerminal() {
        if (loaded) {
            try {
                resetTerminal0();
                return;
            } catch (UnsatisfiedLinkError e) {
                // Fallback
            }
        }
        if (sttyRawModeActive) {
            try {
                new ProcessBuilder("sh", "-c", "stty sane < /dev/tty").start().waitFor();
                sttyRawModeActive = false;
                // Show cursor
                System.out.print("\033[?25h\033[0m\n");
                System.out.flush();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    public static synchronized void clearScreen() {
        if (loaded) {
            try {
                clearScreen0();
                return;
            } catch (UnsatisfiedLinkError e) {
                // Fallback
            }
        }
        // ANSI escape sequence to clear screen, move cursor home and clear scrollback buffer
        System.out.print("\u001B[2J\u001B[H\u001B[3J");
        System.out.flush();
    }

    public static synchronized void printAt(int x, int y, String text) {
        if (loaded) {
            try {
                printAt0(x, y, text);
                return;
            } catch (UnsatisfiedLinkError e) {
                // Fallback
            }
        }
        // ANSI escape sequence to position cursor at y, x and print text
        System.out.print("\u001B[" + y + ";" + x + "H" + text);
        System.out.flush();
    }

    public static synchronized int readKey() {
        if (loaded) {
            try {
                return readKey0();
            } catch (UnsatisfiedLinkError e) {
                // Fallback
            }
        }
        try {
            if (sttyRawModeActive) {
                if (System.in.available() > 0) {
                    int c = System.in.read();
                    if (c == 27) { // Escape sequence parser for fallback mode
                        // Wait up to 50ms for the next bytes of the escape sequence to arrive
                        long start = System.currentTimeMillis();
                        while (System.in.available() == 0 && (System.currentTimeMillis() - start) < 50) {
                            ThreadManager.spinWait();
                        }
                        if (System.in.available() > 0) {
                            int c2 = System.in.read();
                            if (c2 == '[') {
                                start = System.currentTimeMillis();
                                while (System.in.available() == 0 && (System.currentTimeMillis() - start) < 50) {
                                    ThreadManager.spinWait();
                                }
                                if (System.in.available() > 0) {
                                    int c3 = System.in.read();
                                    if (c3 == 'A') return 1000; // UP Arrow
                                    if (c3 == 'B') return 1001; // DOWN Arrow
                                    if (c3 == 'C') return 1002; // RIGHT Arrow
                                    if (c3 == 'D') return 1003; // LEFT Arrow
                                }
                            }
                        }
                    }
                    return c;
                }
            } else {
                // In canonical/cooked mode (waiting for TUI toggle), perform a blocking read
                int c = System.in.read();
                if (c == 27) {
                    // Escape sequence check (if any)
                    if (System.in.available() > 0) {
                        int c2 = System.in.read();
                        if (c2 == '[') {
                            if (System.in.available() > 0) {
                                int c3 = System.in.read();
                                if (c3 == 'A') return 1000;
                                if (c3 == 'B') return 1001;
                                if (c3 == 'C') return 1002;
                                if (c3 == 'D') return 1003;
                            }
                        }
                    }
                }
                return c;
            }
        } catch (Exception e) {
            // Ignored
        }
        return -1;
    }

    public static synchronized boolean saveConfig(String filepath, String content) {
        if (loaded) {
            try {
                return saveConfig0(filepath, content);
            } catch (UnsatisfiedLinkError e) {
                // Fallback
            }
        }
        try (FileWriter fw = new FileWriter(filepath)) {
            fw.write(content);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int cachedWidth = 110;
    private static int cachedHeight = 24;
    private static long lastSizeCheck = 0;

    public static synchronized int getTerminalWidth() {
        updateTerminalSize();
        return Math.max(20, cachedWidth);
    }

    public static synchronized int getTerminalHeight() {
        updateTerminalSize();
        return Math.max(5, cachedHeight);
    }

    private static void updateTerminalSize() {
        long now = System.currentTimeMillis();
        if (now - lastSizeCheck < 200) {
            return;
        }
        lastSizeCheck = now;
        if (loaded) {
            try {
                int w = getTerminalWidth0();
                int h = getTerminalHeight0();
                if (w > 0 && h > 0) {
                    cachedWidth = w;
                    cachedHeight = h;
                    return;
                }
            } catch (UnsatisfiedLinkError e) {
                // Fallback
            }
        }
        boolean ttySuccess = true;

        int width = readTerminalDimension(new ProcessBuilder("sh", "-c", "tput cols < /dev/tty"),-1);
        int height = readTerminalDimension(new ProcessBuilder("sh", "-c", "tput lines < /dev/tty"),-1);

        if (width == -1 || height == -1) {
            ttySuccess = false;
        }

        if (ttySuccess) {
            cachedWidth = width;
            cachedHeight = height;
        } else {
            cachedWidth = readTerminalDimension(
                new ProcessBuilder("sh", "-c", "tput cols"),
                cachedWidth
            );

            cachedHeight = readTerminalDimension(
                new ProcessBuilder("sh", "-c", "tput lines"),
                cachedHeight
            );
        }
    }

    private static int readTerminalDimension(ProcessBuilder processBuilder, int defaultValue) {
        try {
            Process process = processBuilder.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    process.waitFor();
                    return Integer.parseInt(line.trim());
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return defaultValue;
    }
}