import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;

public class EntryPoint {
    // Default configuration values
    private static String configFile = "/etc/tron/mainnet_config.conf";
    private static int configP2pPort = 28888;
    private static int configFullNodePort = 8090;
    private static String configSolidityNodePort = null;
    private static double configVmMaxTimeRatio = 20.0;
    private static int rpcFullNode = 8545;
    private static int rpcSolidityNode = 8555;
    
    // Event plugin defaults (mainnet)
    private static boolean configEventPluginEnabled = true;
    private static String configEventPluginPath = "";
    private static String configEventPluginKafkaServer = "kafka:9092";
    private static boolean configBlockTriggerEnabled = true;
    private static boolean configTransactionTriggerEnabled = true;
    private static boolean configContracteventTriggerEnabled = true;
    private static boolean configContractlogTriggerEnabled = false;
    private static boolean configSolidityBlockTriggerEnabled = false;
    private static boolean configSolidityEventTriggerEnabled = false;
    private static boolean configSolidityLogTriggerEnabled = false;
    private static String configContractAddressFilter = "\"\"";
    private static String configContractTopicFilter = "\"\"";
    
    private static String esFlag = "";
    private static String witnessFlag = "";
    
    private static void validateBoolean(String varName, String varValue) {
        if (varValue != null && !varValue.isEmpty() && 
            !varValue.equals("true") && !varValue.equals("false")) {
            System.err.println("Invalid " + varName + ": " + varValue + ". Must be one of: \"true\", \"false\"");
            System.exit(1);
        }
    }
    
    private static String buildFilter(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] items = value.split("\\s+");
        if (items.length == 0) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < items.length; i++) {
            if (i == items.length - 1) {
                result.append("\"").append(items[i]).append("\"");
            } else {
                result.append("\"").append(items[i]).append("\",\n      ");
            }
        }
        return result.toString();
    }
    
    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty() ? value : defaultValue;
    }
    
    private static String getEnv(String name) {
        return System.getenv(name);
    }
    
    public static void main(String[] args) {
        try {
            // Validate network
            String network = getEnv("NETWORK");
            if (network != null && !network.isEmpty() && 
                !network.equals("mainnet") && !network.equals("nile")) {
                System.err.println("Invalid NETWORK: " + network + ". Must be one of: \"mainnet\", \"nile\"");
                System.exit(1);
            }
            
            // Set config file based on network
            if ("nile".equals(network)) {
                configFile = "/etc/tron/nile_config.conf";
            }
            
            // Validate and set witness mode
            String witnessMode = getEnv("WITNESS_MODE");
            validateBoolean("WITNESS_MODE", witnessMode);
            if ("true".equals(witnessMode)) {
                witnessFlag = "--witness";
            }
            
            // Validate and set event plugin
            String eventPluginEnabled = getEnv("EVENT_PLUGIN_ENABLED");
            validateBoolean("EVENT_PLUGIN_ENABLED", eventPluginEnabled);
            if (eventPluginEnabled != null && !eventPluginEnabled.isEmpty()) {
                configEventPluginEnabled = Boolean.parseBoolean(eventPluginEnabled);
            }
            
            // Override ports and settings from environment variables
            String p2pPort = getEnv("P2P_PORT");
            if (p2pPort != null && !p2pPort.isEmpty()) {
                configP2pPort = Integer.parseInt(p2pPort);
            }
            
            String fullNodePort = getEnv("FULL_NODE_PORT");
            if (fullNodePort != null && !fullNodePort.isEmpty()) {
                configFullNodePort = Integer.parseInt(fullNodePort);
            }
            
            String solidityNodePort = getEnv("SOLIDITY_NODE_PORT");
            if (solidityNodePort != null && !solidityNodePort.isEmpty()) {
                configSolidityNodePort = solidityNodePort;
            }
            
            String vmMaxTimeRatio = getEnv("VM_MAX_TIME_RATIO");
            if (vmMaxTimeRatio != null && !vmMaxTimeRatio.isEmpty()) {
                configVmMaxTimeRatio = Double.parseDouble(vmMaxTimeRatio);
            }
            
            String rpcFullNodeStr = getEnv("RPC_FULL_NODE");
            if (rpcFullNodeStr != null && !rpcFullNodeStr.isEmpty()) {
                rpcFullNode = Integer.parseInt(rpcFullNodeStr);
            }
            
            String rpcSolidityNodeStr = getEnv("RPC_SOLIDITY_NODE");
            if (rpcSolidityNodeStr != null && !rpcSolidityNodeStr.isEmpty()) {
                rpcSolidityNode = Integer.parseInt(rpcSolidityNodeStr);
            }
            
            // Configure event plugin if enabled
            if (configEventPluginEnabled) {
                String kafkaServer = getEnv("EVENT_PLUGIN_KAFKA_SERVER");
                if (kafkaServer != null && !kafkaServer.isEmpty()) {
                    configEventPluginKafkaServer = kafkaServer;
                }
                if (configEventPluginKafkaServer == null || configEventPluginKafkaServer.isEmpty()) {
                    System.err.println("EVENT_PLUGIN_KAFKA_SERVER must be specified when event plugin is enabled");
                    System.exit(1);
                }
                
                esFlag = "--es";
                configEventPluginPath = "/usr/local/tron/plugins/plugin-kafka-1.0.0.zip";
                
                // Validate and set trigger flags
                String blockTrigger = getEnv("EVENT_PLUGIN_BLOCK_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_BLOCK_TRIGGER_ENABLED", blockTrigger);
                if (blockTrigger != null && !blockTrigger.isEmpty()) {
                    configBlockTriggerEnabled = Boolean.parseBoolean(blockTrigger);
                }
                
                String transactionTrigger = getEnv("EVENT_PLUGIN_TRANSACTION_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_TRANSACTION_TRIGGER_ENABLED", transactionTrigger);
                if (transactionTrigger != null && !transactionTrigger.isEmpty()) {
                    configTransactionTriggerEnabled = Boolean.parseBoolean(transactionTrigger);
                }
                
                String contracteventTrigger = getEnv("EVENT_PLUGIN_CONTRACTEVENT_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_CONTRACTEVENT_TRIGGER_ENABLED", contracteventTrigger);
                if (contracteventTrigger != null && !contracteventTrigger.isEmpty()) {
                    configContracteventTriggerEnabled = Boolean.parseBoolean(contracteventTrigger);
                }
                
                String contractlogTrigger = getEnv("EVENT_PLUGIN_CONTRACTLOG_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_CONTRACTLOG_TRIGGER_ENABLED", contractlogTrigger);
                if (contractlogTrigger != null && !contractlogTrigger.isEmpty()) {
                    configContractlogTriggerEnabled = Boolean.parseBoolean(contractlogTrigger);
                }
                
                String solidityBlockTrigger = getEnv("EVENT_PLUGIN_SOLIDITY_BLOCK_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_SOLIDITY_BLOCK_TRIGGER_ENABLED", solidityBlockTrigger);
                if (solidityBlockTrigger != null && !solidityBlockTrigger.isEmpty()) {
                    configSolidityBlockTriggerEnabled = Boolean.parseBoolean(solidityBlockTrigger);
                }
                
                String solidityEventTrigger = getEnv("EVENT_PLUGIN_SOLIDITY_EVENT_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_SOLIDITY_EVENT_TRIGGER_ENABLED", solidityEventTrigger);
                if (solidityEventTrigger != null && !solidityEventTrigger.isEmpty()) {
                    configSolidityEventTriggerEnabled = Boolean.parseBoolean(solidityEventTrigger);
                }
                
                String solidityLogTrigger = getEnv("EVENT_PLUGIN_SOLIDITY_LOG_TRIGGER_ENABLED");
                validateBoolean("EVENT_PLUGIN_SOLIDITY_LOG_TRIGGER_ENABLED", solidityLogTrigger);
                if (solidityLogTrigger != null && !solidityLogTrigger.isEmpty()) {
                    configSolidityLogTriggerEnabled = Boolean.parseBoolean(solidityLogTrigger);
                }
                
                // Build filter strings
                String addressFilter = buildFilter("EVENT_PLUGIN_ADDRESS_FILTER");
                if (addressFilter != null) {
                    configContractAddressFilter = addressFilter;
                }
                
                String topicFilter = buildFilter("EVENT_PLUGIN_TOPIC_FILTER");
                if (topicFilter != null) {
                    configContractTopicFilter = topicFilter;
                }
            }
            
            // Update config file with replacements
            Path configPath = Paths.get(configFile);
            
            // Validate config file exists
            if (!Files.exists(configPath)) {
                System.err.println("ERROR: Config file does not exist: " + configFile);
                System.exit(1);
            }
            
            if (!Files.isReadable(configPath)) {
                System.err.println("ERROR: Config file is not readable: " + configFile);
                System.exit(1);
            }
            
            System.out.println("Reading config file: " + configFile);
            String content = new String(Files.readAllBytes(configPath));
            System.out.println("Config file size: " + content.length() + " bytes");
            
            // Regex replacements (like sed -i)
            content = content.replaceAll("listen\\.port = .*", "listen.port = " + configP2pPort);
            content = content.replaceAll("fullNodePort = .*", "fullNodePort = " + configFullNodePort);
            if (configSolidityNodePort != null) {
                content = content.replaceAll("solidityPort = .*", "solidityPort = " + configSolidityNodePort);
            }
            
            // Placeholder replacements
            content = content.replace("{VM_MAX_TIME_RATIO_PLACEHOLDER}", String.valueOf(configVmMaxTimeRatio));
            content = content.replace("{PLUGIN_PATH_PLACEHOLDER}", configEventPluginPath);
            content = content.replace("{KAFKA_SERVER_PLACEHOLDER}", configEventPluginKafkaServer);
            content = content.replace("{BLOCK_TRIGGER_PLACEHOLDER}", String.valueOf(configBlockTriggerEnabled));
            content = content.replace("{TRANSACTION_TRIGGER_PLACEHOLDER}", String.valueOf(configTransactionTriggerEnabled));
            content = content.replace("{CONTRACTEVENT_TRIGGER_PLACEHOLDER}", String.valueOf(configContracteventTriggerEnabled));
            content = content.replace("{CONTRACTLOG_TRIGGER_PLACEHOLDER}", String.valueOf(configContractlogTriggerEnabled));
            content = content.replace("{SOLIDITY_BLOCK_TRIGGER_PLACEHOLDER}", String.valueOf(configSolidityBlockTriggerEnabled));
            content = content.replace("{SOLIDITY_EVENT_TRIGGER_PLACEHOLDER}", String.valueOf(configSolidityEventTriggerEnabled));
            content = content.replace("{SOLIDITY_LOG_TRIGGER_PLACEHOLDER}", String.valueOf(configSolidityLogTriggerEnabled));
            content = content.replace("{CONTRACT_ADDRESS_FILTER_PLACEHOLDER}", configContractAddressFilter);
            content = content.replace("{CONTRACT_TOPIC_FILTER_PLACEHOLDER}", configContractTopicFilter);
            content = content.replace("{RPC_FULL_NODE}", String.valueOf(rpcFullNode));
            content = content.replace("{RPC_SOLIDITY_NODE}", String.valueOf(rpcSolidityNode));
            content = content.replace("{FULL_NODE_PORT}", String.valueOf(configFullNodePort));
            if (configSolidityNodePort != null) {
                content = content.replace("{SOLIDITY_NODE_PORT}", configSolidityNodePort);
            }
            
            Files.write(configPath, content.getBytes());
            System.out.println("Config file updated successfully");
            
            // Print config file content before running
            System.out.println("\n=== Config file content ===");
            System.out.println(content);
            System.out.println("=== End of config file ===\n");
            
            // Validate JAR file exists
            Path jarPath = Paths.get("/usr/local/tron/FullNode.jar");
            if (!Files.exists(jarPath)) {
                System.err.println("ERROR: FullNode.jar does not exist: /usr/local/tron/FullNode.jar");
                System.exit(1);
            }
            System.out.println("FullNode.jar found: " + Files.size(jarPath) + " bytes");
            
            // Calculate and print MD5 hash
            try {
                String md5Hash = calculateMD5(jarPath);
                System.out.println("FullNode.jar MD5: " + md5Hash);
            } catch (IOException e) {
                System.err.println("WARNING: Failed to calculate MD5 hash: " + e.getMessage());
            }
            
            // Build and execute Java command based on network
            // Get heap size from environment variable, with defaults
            String heapSizeStr = getEnv("JAVA_HEAP_SIZE");
            int heapSizeGB;
            if (heapSizeStr != null && !heapSizeStr.isEmpty()) {
                try {
                    heapSizeGB = Integer.parseInt(heapSizeStr);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid JAVA_HEAP_SIZE: " + heapSizeStr + ", using default");
                    heapSizeGB = (network == null || network.isEmpty() || "mainnet".equals(network)) ? 48 : 8;
                }
            } else {
                // Default: 48GB for mainnet (64GB system), 8GB for nile
                heapSizeGB = (network == null || network.isEmpty() || "mainnet".equals(network)) ? 48 : 8;
            }
            
            // Get CPU count for GC thread optimization
            int cpuCount = Runtime.getRuntime().availableProcessors();
            int gcThreads = Math.max(2, Math.min(cpuCount / 4, 16)); // GC threads: 2-16 based on CPU count
            
            // Use G1GC for large heaps (better than CMS for >16GB)
            String javaOptsCommon;
            if (heapSizeGB >= 16) {
                // G1GC optimized for large heaps and multiple CPUs
                javaOptsCommon = String.format(
                    "-XX:ReservedCodeCacheSize=512m " +
                    "-XX:MetaspaceSize=512m " +
                    "-XX:MaxMetaspaceSize=4G " +
                    "-XX:MaxDirectMemorySize=4G " +
                    "-XX:+HeapDumpOnOutOfMemoryError " +
                    "-XX:HeapDumpPath=/data/heap_dump.hprof " +
                    "-XX:+PrintGCDetails " +
                    "-XX:+PrintGCDateStamps " +
                    "-XX:+PrintGCApplicationStoppedTime " +
                    "-Xloggc:/data/gc.log " +
                    "-XX:+UseG1GC " +
                    "-XX:MaxGCPauseMillis=200 " +
                    "-XX:G1HeapRegionSize=16m " +
                    "-XX:InitiatingHeapOccupancyPercent=45 " +
                    "-XX:ConcGCThreads=%d " +
                    "-XX:ParallelGCThreads=%d " +
                    "-XX:+ParallelRefProcEnabled " +
                    "-XX:+UseStringDeduplication " +
                    "-XX:+DisableExplicitGC",
                    gcThreads, gcThreads * 2
                );
            } else {
                // CMS for smaller heaps
                javaOptsCommon = "-XX:ReservedCodeCacheSize=256m " +
                    "-XX:MetaspaceSize=512m " +
                    "-XX:MaxMetaspaceSize=2G " +
                    "-XX:MaxDirectMemorySize=2G " +
                    "-XX:+HeapDumpOnOutOfMemoryError " +
                    "-XX:+PrintGCDetails " +
                    "-XX:+PrintGCDateStamps " +
                    "-XX:+UseConcMarkSweepGC " +
                    "-XX:NewRatio=2 " +
                    "-XX:+CMSScavengeBeforeRemark " +
                    "-XX:+ParallelRefProcEnabled " +
                    "-XX:+UseCMSInitiatingOccupancyOnly " +
                    "-XX:CMSInitiatingOccupancyFraction=70 " +
                    "-XX:ParallelGCThreads=" + gcThreads;
            }
            
            // Add NUMA support for large heaps on multi-socket systems
            String numaOpts = "";
            if (heapSizeGB >= 32 && cpuCount >= 16) {
                numaOpts = " -XX:+UseNUMA";
            } else {
                numaOpts = " -XX:-UseNUMA";
            }
            
            // For very large heaps, AlwaysPreTouch can take a long time or fail
            // Only enable it for heaps <= 32GB to avoid startup issues
            String alwaysPreTouch = (heapSizeGB <= 32) ? " -XX:+AlwaysPreTouch" : " -XX:-AlwaysPreTouch";
            
            String javaOpts = javaOptsCommon + 
                String.format(" -Xms%dG -Xmx%dG", heapSizeGB, heapSizeGB) +
                alwaysPreTouch +
                numaOpts;
            
            // Build command
            List<String> command = new ArrayList<>();
            command.add("java");
            // Split javaOpts and add each as separate argument
            for (String opt : javaOpts.split("\\s+")) {
                if (!opt.isEmpty()) {
                    command.add(opt);
                }
            }
            command.add("-jar");
            command.add("/usr/local/tron/FullNode.jar");
            command.add("-c");
            command.add(configFile);
            command.add("-d");
            command.add("/data");
            if (!esFlag.isEmpty()) {
                command.add(esFlag);
            }
            if (!witnessFlag.isEmpty()) {
                command.add(witnessFlag);
            }
            
            System.out.println("Executing: " + String.join(" ", command));
            System.out.println("Working directory: /data");
            System.out.println("Heap size: " + heapSizeGB + "GB");
            System.out.println("CPU count: " + cpuCount);
            System.out.println("GC threads: " + gcThreads);
            
            // Warn if heap size is very large
            if (heapSizeGB > 32) {
                System.out.println("WARNING: Large heap size (" + heapSizeGB + "GB) may cause startup issues.");
                System.out.println("If the process fails to start, try reducing JAVA_HEAP_SIZE (e.g., 32 or 40)");
            }
            
            // Check available memory (rough estimate)
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory();
            long totalMemory = runtime.totalMemory();
            System.out.println("EntryPoint JVM - Max memory: " + (maxMemory / 1024 / 1024 / 1024) + "GB, Total: " + (totalMemory / 1024 / 1024 / 1024) + "GB");
            
            // Try to check system memory and Docker limits
            try {
                // Check /sys/fs/cgroup/memory/memory.limit_in_bytes (Docker memory limit)
                Path dockerMemLimit = Paths.get("/sys/fs/cgroup/memory/memory.limit_in_bytes");
                if (Files.exists(dockerMemLimit)) {
                    String memLimitStr = new String(Files.readAllBytes(dockerMemLimit)).trim();
                    try {
                        long memLimitBytes = Long.parseLong(memLimitStr);
                        if (memLimitBytes < Long.MAX_VALUE) {
                            long memLimitGB = memLimitBytes / 1024 / 1024 / 1024;
                            System.out.println("Docker memory limit: " + memLimitGB + "GB");
                            if (memLimitGB < heapSizeGB + 4) {
                                System.err.println("WARNING: Docker memory limit (" + memLimitGB + "GB) may be too low for " + heapSizeGB + "GB heap.");
                                System.err.println("Recommended: Set Docker memory limit to at least " + (heapSizeGB + 4) + "GB");
                            }
                        }
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
                
                // Check /proc/meminfo for system memory
                Path memInfo = Paths.get("/proc/meminfo");
                if (Files.exists(memInfo)) {
                    List<String> memLines = Files.readAllLines(memInfo);
                    for (String line : memLines) {
                        if (line.startsWith("MemTotal:")) {
                            String[] parts = line.split("\\s+");
                            if (parts.length >= 2) {
                                try {
                                    long memTotalKB = Long.parseLong(parts[1]);
                                    long memTotalGB = memTotalKB / 1024 / 1024;
                                    System.out.println("System total memory: " + memTotalGB + "GB");
                                } catch (NumberFormatException e) {
                                    // Ignore
                                }
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore - these files may not be available
            }
            
            System.out.flush();
            
            // Execute the command
            ProcessBuilder pb = new ProcessBuilder(command);
            // Don't redirect error stream - capture both separately to see JVM errors
            pb.redirectErrorStream(false);
            
            // Set working directory
            pb.directory(new File("/data"));
            
            Process process = pb.start();
            System.out.println("Process started, PID: " + getProcessId(process));
            System.out.flush();
            
            // Use StringBuilders to collect both stdout and stderr
            StringBuilder outputBuffer = new StringBuilder();
            StringBuilder errorBuffer = new StringBuilder();
            final boolean[] processEnded = {false};
            
            // Stream stdout in real-time
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null || !processEnded[0]) {
                        if (line != null) {
                            System.out.println(line);
                            System.out.flush();
                            outputBuffer.append(line).append("\n");
                        } else {
                            // No more lines, but process might still be running
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    if (!processEnded[0]) {
                        System.err.println("Error reading process stdout: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
            outputThread.start();
            
            // Stream stderr in real-time (important for JVM startup errors)
            Thread errorThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null || !processEnded[0]) {
                        if (line != null) {
                            System.err.println("[stderr] " + line);
                            System.err.flush();
                            errorBuffer.append(line).append("\n");
                        } else {
                            // No more lines, but process might still be running
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    if (!processEnded[0]) {
                        System.err.println("Error reading process stderr: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
            errorThread.start();
            
            // Give threads a moment to start reading before checking process status
            // This helps catch immediate failures
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Check if process has already exited (immediate failure)
            boolean exitedImmediately = false;
            try {
                int quickExitCode = process.exitValue(); // throws exception if still running
                exitedImmediately = true;
                System.err.println("WARNING: Process exited immediately with code: " + quickExitCode);
                System.err.println("This usually indicates a JVM startup failure.");
                System.err.flush();
            } catch (IllegalThreadStateException e) {
                // Process is still running, which is normal
            }
            
            // Wait for process to complete
            int exitCode = process.waitFor();
            processEnded[0] = true;
            
            // Give output threads more time to finish reading, especially for immediate failures
            int joinTimeout = exitedImmediately ? 3000 : 2000;
            outputThread.join(joinTimeout);
            errorThread.join(joinTimeout);
            
            // Try to read any remaining bytes from stderr if process exited quickly
            if (exitedImmediately && errorBuffer.length() == 0) {
                try {
                    // Try reading raw bytes from error stream
                    InputStream errorStream = process.getErrorStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead = errorStream.read(buffer);
                    if (bytesRead > 0) {
                        String errorText = new String(buffer, 0, bytesRead);
                        System.err.println("[stderr-raw] " + errorText);
                        errorBuffer.append(errorText);
                    }
                } catch (Exception e) {
                    // Ignore - stream may already be closed
                }
            }
            
            if (exitCode != 0) {
                System.err.println("\n=== Process exited with code: " + exitCode + " ===");
                if (errorBuffer.length() > 0) {
                    System.err.println("\n=== Error output (stderr) ===");
                    System.err.println(errorBuffer.toString());
                    System.err.println("=== End of error output ===");
                }
                if (outputBuffer.length() > 0) {
                    System.err.println("\n=== Standard output (stdout) ===");
                    System.err.println(outputBuffer.toString());
                    System.err.println("=== End of standard output ===");
                }
                if (outputBuffer.length() == 0 && errorBuffer.length() == 0) {
                    System.err.println("No output captured from process (neither stdout nor stderr).");
                    System.err.println("This usually means:");
                    System.err.println("  1. JVM failed to start (check memory allocation)");
                    System.err.println("  2. Config file has errors");
                    System.err.println("  3. JAR file is corrupted");
                    System.err.println("\nTrying to allocate " + heapSizeGB + "GB heap.");
                    System.err.println("Available CPUs: " + cpuCount);
                    System.err.println("EntryPoint JVM max memory: " + (maxMemory / 1024 / 1024 / 1024) + "GB");
                    System.err.println("\nTroubleshooting:");
                    System.err.println("  - Check if system has enough memory for " + heapSizeGB + "GB heap");
                    System.err.println("  - Try reducing JAVA_HEAP_SIZE environment variable");
                    System.err.println("  - Check Docker container memory limits if running in Docker");
                }
                System.err.flush();
            }
            
            System.exit(exitCode);
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    // Helper method to get process ID (may not work on all systems)
    private static long getProcessId(Process process) {
        try {
            if (process.getClass().getName().equals("java.lang.UNIXProcess")) {
                java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
                field.setAccessible(true);
                return field.getLong(process);
            }
        } catch (Exception e) {
            // Ignore
        }
        return -1;
    }
    
    // Helper method to calculate MD5 hash of a file
    private static String calculateMD5(Path filePath) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream is = Files.newInputStream(filePath);
                 BufferedInputStream bis = new BufferedInputStream(is)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = bis.read(buffer)) != -1) {
                    md.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }
    }
}

