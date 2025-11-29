import java.io.*;
import java.nio.file.*;
import java.util.*;

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
    
    /**
     * Detect available system memory in GB.
     * Tries Docker memory limit first, then /proc/meminfo.
     * Returns -1 if detection fails.
     */
    private static long detectSystemMemoryGB() {
        try {
            // First, check Docker memory limit (more accurate in containers)
            Path dockerMemLimit = Paths.get("/sys/fs/cgroup/memory/memory.limit_in_bytes");
            if (Files.exists(dockerMemLimit)) {
                String memLimitStr = new String(Files.readAllBytes(dockerMemLimit)).trim();
                try {
                    long memLimitBytes = Long.parseLong(memLimitStr);
                    if (memLimitBytes > 0 && memLimitBytes < Long.MAX_VALUE) {
                        long memLimitGB = memLimitBytes / 1024 / 1024 / 1024;
                        if (memLimitGB > 0) {
                            System.out.println("Detected Docker memory limit: " + memLimitGB + "GB");
                            return memLimitGB;
                        }
                    }
                } catch (NumberFormatException e) {
                    // Continue to try /proc/meminfo
                }
            }
            
            // Fall back to /proc/meminfo for system memory
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
                                if (memTotalGB > 0) {
                                    System.out.println("Detected system total memory: " + memTotalGB + "GB");
                                    return memTotalGB;
                                }
                            } catch (NumberFormatException e) {
                                // Ignore
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not detect system memory: " + e.getMessage());
        }
        return -1;
    }
    
    /**
     * Calculate optimal heap size based on available system memory.
     * Optimized to use 65% of Docker memory limit to leave room for off-heap memory.
     * Returns calculated heap size in GB, or -1 if calculation fails.
     */
    private static int calculateOptimalHeapSize(long systemMemoryGB, String network) {
        if (systemMemoryGB <= 0) {
            return -1;
        }
        
        // Use 65% of available memory for heap (reduced from 80% to prevent OOM)
        // This leaves 35% for OS, JVM overhead, off-heap memory, and system buffers
        int calculatedHeapGB = (int) (systemMemoryGB * 0.65);
        
        // Apply network-specific minimums
        int minHeapGB = (network == null || network.isEmpty() || "mainnet".equals(network)) ? 8 : 4;
        if (calculatedHeapGB < minHeapGB) {
            System.out.println("Warning: Calculated heap size (" + calculatedHeapGB + "GB) is below minimum (" + minHeapGB + "GB), using minimum");
            return minHeapGB;
        }
        
        // Apply maximum heap size limit optimized for 50GB Docker containers
        // For 50GB limit: 65% = 32.5GB heap, leaving 17.5GB for off-heap (more conservative)
        int maxHeapGB = (network == null || network.isEmpty() || "mainnet".equals(network)) ? 32 : 12;
        if (calculatedHeapGB > maxHeapGB) {
            System.out.println("Warning: Calculated heap size (" + calculatedHeapGB + "GB) exceeds maximum (" + maxHeapGB + "GB), capping at maximum for optimal performance");
            return maxHeapGB;
        }
        
        return calculatedHeapGB;
    }
    
    /**
     * Calculate optimal RPC thread count based on CPU cores.
     * Uses 1x CPU cores to reduce context switching and CPU overhead.
     */
    private static int calculateRpcThreadCount(int cpuCount) {
        // Use 1x CPU cores to reduce context switching overhead
        int threads = cpuCount;
        // Cap at 32 threads to avoid excessive context switching
        return Math.min(threads, 32);
    }
    
    /**
     * Calculate optimal max connections based on available RAM.
     * More RAM allows for more concurrent connections, but reduced to lower overhead.
     */
    private static int calculateMaxConnections(long systemMemoryGB) {
        if (systemMemoryGB <= 0) {
            return 100; // Default
        }
        // Scale connections based on RAM: ~5 connections per GB (reduced from 10)
        int connections = (int) (systemMemoryGB * 5);
        // Minimum 100, maximum 1000 (reduced from 2000)
        return Math.max(100, Math.min(connections, 1000));
    }
    
    /**
     * Calculate optimal max HTTP connections based on RAM.
     */
    private static int calculateMaxHttpConnections(long systemMemoryGB) {
        if (systemMemoryGB <= 0) {
            return 50; // Default
        }
        // Scale HTTP connections: ~3 connections per GB (reduced from 5)
        int connections = (int) (systemMemoryGB * 3);
        // Minimum 50, maximum 500 (reduced from 1000)
        return Math.max(50, Math.min(connections, 500));
    }
    
    /**
     * Calculate optimal storage cache size based on available RAM.
     * Optimized cache sizes for better database performance.
     */
    private static long calculateStorageCacheSize(long systemMemoryGB) {
        if (systemMemoryGB <= 0) {
            return 536870912L; // Default 512MB
        }
        // Optimized cache sizes for better LevelDB performance
        if (systemMemoryGB >= 64) {
            return 2147483648L; // 2GB for 64GB+ systems
        } else if (systemMemoryGB >= 50) {
            return 1073741824L; // 1GB for 50GB+ systems (optimized for 50GB containers)
        } else if (systemMemoryGB >= 32) {
            return 536870912L; // 512MB for 32GB+ systems
        } else if (systemMemoryGB >= 16) {
            return 268435456L; // 256MB for 16GB+ systems
        } else {
            return 134217728L; // 128MB for smaller systems
        }
    }
    
    /**
     * Calculate optimal storage write buffer size based on RAM.
     * Optimized buffer sizes for better write performance.
     */
    private static long calculateStorageWriteBufferSize(long systemMemoryGB) {
        if (systemMemoryGB <= 0) {
            return 67108864L; // Default 64MB
        }
        // Optimized write buffer sizes for better performance
        if (systemMemoryGB >= 64) {
            return 134217728L; // 128MB
        } else if (systemMemoryGB >= 50) {
            return 134217728L; // 128MB for 50GB+ containers
        } else if (systemMemoryGB >= 32) {
            return 67108864L; // 64MB
        } else {
            return 33554432L; // 32MB
        }
    }
    
    /**
     * Calculate optimal max open files based on RAM.
     */
    private static int calculateMaxOpenFiles(long systemMemoryGB) {
        if (systemMemoryGB <= 0) {
            return 50000; // Default
        }
        // Scale max open files: 100K for 64GB+, 50K for 32GB+, 25K otherwise
        if (systemMemoryGB >= 64) {
            return 100000;
        } else if (systemMemoryGB >= 32) {
            return 50000;
        } else {
            return 25000;
        }
    }
    
    /**
     * Calculate optimal DB compaction threads based on CPU cores.
     */
    private static int calculateDbCompactThreads(int cpuCount) {
        // Use CPU/2 for compaction threads when sync is done
        return Math.max(2, cpuCount / 2);
    }
    
    /**
     * Calculate optimal DB level base size based on RAM.
     */
    private static int calculateDbMaxBytesForLevelBase(long systemMemoryGB) {
        if (systemMemoryGB >= 64) {
            return 512; // 512MB for 64GB+ systems
        } else if (systemMemoryGB >= 32) {
            return 256; // 256MB for 32GB+ systems
        } else {
            return 128; // 128MB for smaller systems
        }
    }
    
    /**
     * Calculate optimal global QPS based on CPU and RAM.
     * Reduced to prevent excessive CPU usage.
     */
    private static int calculateGlobalQps(int cpuCount, long systemMemoryGB) {
        // Base QPS on CPU: 2000 per core (reduced from 5000 to lower CPU usage)
        int baseQps = cpuCount * 2000;
        // Scale with RAM: 1.3x for 64GB+, 1.2x for 32GB+ (reduced multipliers)
        double multiplier = 1.0;
        if (systemMemoryGB >= 64) {
            multiplier = 1.3;
        } else if (systemMemoryGB >= 32) {
            multiplier = 1.2;
        }
        // Cap at 50000 to prevent excessive CPU usage
        int qps = (int) (baseQps * multiplier);
        return Math.min(qps, 50000);
    }
    
    /**
     * Calculate optimal per-IP QPS based on global QPS.
     */
    private static int calculateGlobalIpQps(int globalQps) {
        // Per-IP QPS is 20% of global QPS
        return globalQps / 5;
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
            
            // Detect system resources for dynamic configuration
            // First, try to read from environment variables (set from tron.yml)
            int cpuCount;
            String cpuCountEnv = getEnv("SYSTEM_CPU_COUNT");
            if (cpuCountEnv != null && !cpuCountEnv.isEmpty()) {
                try {
                    // Try parsing as integer first, then as double if that fails (handles "1.5" -> 2)
                    try {
                        cpuCount = Integer.parseInt(cpuCountEnv);
                    } catch (NumberFormatException e) {
                        // If integer parse fails, try double and round
                        double cpuCountDouble = Double.parseDouble(cpuCountEnv);
                        cpuCount = (int) Math.round(cpuCountDouble);
                        if (cpuCount <= 0) {
                            throw new NumberFormatException("CPU count must be positive");
                        }
                    }
                    System.out.println("Using SYSTEM_CPU_COUNT from environment: " + cpuCount);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid SYSTEM_CPU_COUNT: " + cpuCountEnv + ", falling back to auto-detection");
                    cpuCount = Runtime.getRuntime().availableProcessors();
                }
            } else {
                cpuCount = Runtime.getRuntime().availableProcessors();
            }
            
            long systemMemoryGB;
            String memoryGBEnv = getEnv("SYSTEM_MEMORY_GB");
            if (memoryGBEnv != null && !memoryGBEnv.isEmpty()) {
                try {
                    // Try parsing as long first, then as double if that fails (handles "1.5" -> 2)
                    try {
                        systemMemoryGB = Long.parseLong(memoryGBEnv);
                    } catch (NumberFormatException e) {
                        // If long parse fails, try double and round
                        double memoryGBDouble = Double.parseDouble(memoryGBEnv);
                        systemMemoryGB = Math.round(memoryGBDouble);
                        if (systemMemoryGB <= 0) {
                            throw new NumberFormatException("Memory must be positive");
                        }
                    }
                    System.out.println("Using SYSTEM_MEMORY_GB from environment: " + systemMemoryGB);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid SYSTEM_MEMORY_GB: " + memoryGBEnv + ", falling back to auto-detection");
                    systemMemoryGB = detectSystemMemoryGB();
                }
            } else {
                systemMemoryGB = detectSystemMemoryGB();
            }
            
            // Calculate optimal configuration values based on CPU and RAM
            int rpcThreadCount = calculateRpcThreadCount(cpuCount);
            int maxConnections = calculateMaxConnections(systemMemoryGB);
            int maxHttpConnections = calculateMaxHttpConnections(systemMemoryGB);
            int maxConnectionsWithSameIp = Math.max(5, maxConnections / 20); // 5% of max connections
            long storageCacheSize = calculateStorageCacheSize(systemMemoryGB);
            long storageWriteBufferSize = calculateStorageWriteBufferSize(systemMemoryGB);
            int maxOpenFiles = calculateMaxOpenFiles(systemMemoryGB);
            int maxOpenFilesM = (int) (maxOpenFiles * 1.5);
            int maxOpenFilesL = maxOpenFiles * 2;
            int dbCompactThreads = calculateDbCompactThreads(cpuCount);
            int dbMaxBytesForLevelBase = calculateDbMaxBytesForLevelBase(systemMemoryGB);
            int dbTargetFileSizeBase = dbMaxBytesForLevelBase;
            int globalQps = calculateGlobalQps(cpuCount, systemMemoryGB);
            int globalIpQps = calculateGlobalIpQps(globalQps);
            
            // RPC-specific calculations
            int rpcMaxConcurrentCalls = Math.min(50, cpuCount * 4); // Scale with CPU, max 50 (reduced from 100 and 8x)
            int rpcFlowControlWindow = systemMemoryGB >= 64 ? 2097152 : 1048576; // 2MB for 64GB+, 1MB otherwise
            int rpcMaxMessageSize = systemMemoryGB >= 64 ? 8388608 : 4194304; // 8MB for 64GB+, 4MB otherwise
            int rpcMaxHeaderListSize = systemMemoryGB >= 64 ? 16384 : 8192; // 16KB for 64GB+, 8KB otherwise
            
            System.out.println("System Resources Detected:");
            System.out.println("  CPU Cores: " + cpuCount);
            System.out.println("  System Memory: " + systemMemoryGB + "GB");
            System.out.println("Dynamic Configuration Calculated:");
            System.out.println("  RPC Threads: " + rpcThreadCount);
            System.out.println("  Max Connections: " + maxConnections);
            System.out.println("  Max HTTP Connections: " + maxHttpConnections);
            System.out.println("  Storage Cache: " + (storageCacheSize / 1024 / 1024) + "MB");
            System.out.println("  Global QPS: " + globalQps);
            System.out.println("  Global IP QPS: " + globalIpQps);
            
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
            
            // Read config file efficiently with explicit charset
            byte[] configBytes = Files.readAllBytes(configPath);
            StringBuilder contentBuilder = new StringBuilder(configBytes.length + 1024); // Pre-allocate with extra capacity
            contentBuilder.append(new String(configBytes, java.nio.charset.StandardCharsets.UTF_8));
            String content = contentBuilder.toString();
            
            // Check if config file uses placeholders (for mainnet/nile configs)
            boolean usesPlaceholders = content.contains("{FULL_NODE_PORT}") || 
                                      content.contains("{SOLIDITY_NODE_PORT}") ||
                                      content.contains("{RPC_FULL_NODE}") ||
                                      content.contains("{RPC_SOLIDITY_NODE}") ||
                                      content.contains("{VM_MAX_TIME_RATIO_PLACEHOLDER}");
            
            // Pre-compute all string values once to avoid repeated conversions
            String vmMaxTimeRatioStr = String.valueOf(configVmMaxTimeRatio);
            String blockTriggerStr = String.valueOf(configBlockTriggerEnabled);
            String transactionTriggerStr = String.valueOf(configTransactionTriggerEnabled);
            String contracteventTriggerStr = String.valueOf(configContracteventTriggerEnabled);
            String contractlogTriggerStr = String.valueOf(configContractlogTriggerEnabled);
            String solidityBlockTriggerStr = String.valueOf(configSolidityBlockTriggerEnabled);
            String solidityEventTriggerStr = String.valueOf(configSolidityEventTriggerEnabled);
            String solidityLogTriggerStr = String.valueOf(configSolidityLogTriggerEnabled);
            // Reuse rpcFullNodeStr and rpcSolidityNodeStr - convert final values to strings
            rpcFullNodeStr = String.valueOf(rpcFullNode);
            rpcSolidityNodeStr = String.valueOf(rpcSolidityNode);
            String fullNodePortStr = String.valueOf(configFullNodePort);
            String rpcThreadCountStr = String.valueOf(rpcThreadCount);
            String rpcMaxConcurrentCallsStr = String.valueOf(rpcMaxConcurrentCalls);
            String rpcFlowControlWindowStr = String.valueOf(rpcFlowControlWindow);
            String rpcMaxMessageSizeStr = String.valueOf(rpcMaxMessageSize);
            String rpcMaxHeaderListSizeStr = String.valueOf(rpcMaxHeaderListSize);
            String maxConnectionsStr = String.valueOf(maxConnections);
            String maxConnectionsWithSameIpStr = String.valueOf(maxConnectionsWithSameIp);
            String maxHttpConnectionsStr = String.valueOf(maxHttpConnections);
            String maxOpenFilesStr = String.valueOf(maxOpenFiles);
            String maxOpenFilesMStr = String.valueOf(maxOpenFilesM);
            String maxOpenFilesLStr = String.valueOf(maxOpenFilesL);
            String storageWriteBufferSizeStr = String.valueOf(storageWriteBufferSize);
            String storageCacheSizeStr = String.valueOf(storageCacheSize);
            String dbCompactThreadsStr = String.valueOf(dbCompactThreads);
            String dbMaxBytesForLevelBaseStr = String.valueOf(dbMaxBytesForLevelBase);
            String dbTargetFileSizeBaseStr = String.valueOf(dbTargetFileSizeBase);
            String globalQpsStr = String.valueOf(globalQps);
            String globalIpQpsStr = String.valueOf(globalIpQps);
            
            // Regex replacements (like sed -i) - only if placeholders are not used
            // This is for backward compatibility with configs that don't use placeholders
            if (!usesPlaceholders) {
                content = content.replaceAll("listen\\.port = .*", "listen.port = " + configP2pPort);
                content = content.replaceAll("fullNodePort = .*", "fullNodePort = " + configFullNodePort);
                if (configSolidityNodePort != null) {
                    content = content.replaceAll("solidityPort = .*", "solidityPort = " + configSolidityNodePort);
                }
                contentBuilder = new StringBuilder(content);
            }
            
            // Efficient placeholder replacements using StringBuilder for better performance
            // Replace all placeholders in a single pass through the content
            int index;
            while ((index = contentBuilder.indexOf("{VM_MAX_TIME_RATIO_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{VM_MAX_TIME_RATIO_PLACEHOLDER}".length(), vmMaxTimeRatioStr);
            }
            while ((index = contentBuilder.indexOf("{PLUGIN_PATH_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{PLUGIN_PATH_PLACEHOLDER}".length(), configEventPluginPath);
            }
            while ((index = contentBuilder.indexOf("{KAFKA_SERVER_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{KAFKA_SERVER_PLACEHOLDER}".length(), configEventPluginKafkaServer);
            }
            while ((index = contentBuilder.indexOf("{BLOCK_TRIGGER_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{BLOCK_TRIGGER_PLACEHOLDER}".length(), blockTriggerStr);
            }
            while ((index = contentBuilder.indexOf("{TRANSACTION_TRIGGER_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{TRANSACTION_TRIGGER_PLACEHOLDER}".length(), transactionTriggerStr);
            }
            while ((index = contentBuilder.indexOf("{CONTRACTEVENT_TRIGGER_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{CONTRACTEVENT_TRIGGER_PLACEHOLDER}".length(), contracteventTriggerStr);
            }
            while ((index = contentBuilder.indexOf("{CONTRACTLOG_TRIGGER_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{CONTRACTLOG_TRIGGER_PLACEHOLDER}".length(), contractlogTriggerStr);
            }
            while ((index = contentBuilder.indexOf("{SOLIDITY_BLOCK_TRIGGER_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{SOLIDITY_BLOCK_TRIGGER_PLACEHOLDER}".length(), solidityBlockTriggerStr);
            }
            while ((index = contentBuilder.indexOf("{SOLIDITY_EVENT_TRIGGER_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{SOLIDITY_EVENT_TRIGGER_PLACEHOLDER}".length(), solidityEventTriggerStr);
            }
            while ((index = contentBuilder.indexOf("{SOLIDITY_LOG_TRIGGER_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{SOLIDITY_LOG_TRIGGER_PLACEHOLDER}".length(), solidityLogTriggerStr);
            }
            while ((index = contentBuilder.indexOf("{CONTRACT_ADDRESS_FILTER_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{CONTRACT_ADDRESS_FILTER_PLACEHOLDER}".length(), configContractAddressFilter);
            }
            while ((index = contentBuilder.indexOf("{CONTRACT_TOPIC_FILTER_PLACEHOLDER}")) >= 0) {
                contentBuilder.replace(index, index + "{CONTRACT_TOPIC_FILTER_PLACEHOLDER}".length(), configContractTopicFilter);
            }
            while ((index = contentBuilder.indexOf("{RPC_FULL_NODE}")) >= 0) {
                contentBuilder.replace(index, index + "{RPC_FULL_NODE}".length(), rpcFullNodeStr);
            }
            while ((index = contentBuilder.indexOf("{RPC_SOLIDITY_NODE}")) >= 0) {
                contentBuilder.replace(index, index + "{RPC_SOLIDITY_NODE}".length(), rpcSolidityNodeStr);
            }
            while ((index = contentBuilder.indexOf("{FULL_NODE_PORT}")) >= 0) {
                contentBuilder.replace(index, index + "{FULL_NODE_PORT}".length(), fullNodePortStr);
            }
            
            // Dynamic configuration placeholder replacements
            while ((index = contentBuilder.indexOf("{RPC_THREAD_COUNT}")) >= 0) {
                contentBuilder.replace(index, index + "{RPC_THREAD_COUNT}".length(), rpcThreadCountStr);
            }
            while ((index = contentBuilder.indexOf("{RPC_MAX_CONCURRENT_CALLS}")) >= 0) {
                contentBuilder.replace(index, index + "{RPC_MAX_CONCURRENT_CALLS}".length(), rpcMaxConcurrentCallsStr);
            }
            while ((index = contentBuilder.indexOf("{RPC_FLOW_CONTROL_WINDOW}")) >= 0) {
                contentBuilder.replace(index, index + "{RPC_FLOW_CONTROL_WINDOW}".length(), rpcFlowControlWindowStr);
            }
            while ((index = contentBuilder.indexOf("{RPC_MAX_MESSAGE_SIZE}")) >= 0) {
                contentBuilder.replace(index, index + "{RPC_MAX_MESSAGE_SIZE}".length(), rpcMaxMessageSizeStr);
            }
            while ((index = contentBuilder.indexOf("{RPC_MAX_HEADER_LIST_SIZE}")) >= 0) {
                contentBuilder.replace(index, index + "{RPC_MAX_HEADER_LIST_SIZE}".length(), rpcMaxHeaderListSizeStr);
            }
            while ((index = contentBuilder.indexOf("{MAX_CONNECTIONS}")) >= 0) {
                contentBuilder.replace(index, index + "{MAX_CONNECTIONS}".length(), maxConnectionsStr);
            }
            while ((index = contentBuilder.indexOf("{MAX_CONNECTIONS_WITH_SAME_IP}")) >= 0) {
                contentBuilder.replace(index, index + "{MAX_CONNECTIONS_WITH_SAME_IP}".length(), maxConnectionsWithSameIpStr);
            }
            while ((index = contentBuilder.indexOf("{MAX_HTTP_CONNECT_NUMBER}")) >= 0) {
                contentBuilder.replace(index, index + "{MAX_HTTP_CONNECT_NUMBER}".length(), maxHttpConnectionsStr);
            }
            while ((index = contentBuilder.indexOf("{STORAGE_MAX_OPEN_FILES}")) >= 0) {
                contentBuilder.replace(index, index + "{STORAGE_MAX_OPEN_FILES}".length(), maxOpenFilesStr);
            }
            while ((index = contentBuilder.indexOf("{STORAGE_MAX_OPEN_FILES_M}")) >= 0) {
                contentBuilder.replace(index, index + "{STORAGE_MAX_OPEN_FILES_M}".length(), maxOpenFilesMStr);
            }
            while ((index = contentBuilder.indexOf("{STORAGE_MAX_OPEN_FILES_L}")) >= 0) {
                contentBuilder.replace(index, index + "{STORAGE_MAX_OPEN_FILES_L}".length(), maxOpenFilesLStr);
            }
            while ((index = contentBuilder.indexOf("{STORAGE_WRITE_BUFFER_SIZE}")) >= 0) {
                contentBuilder.replace(index, index + "{STORAGE_WRITE_BUFFER_SIZE}".length(), storageWriteBufferSizeStr);
            }
            while ((index = contentBuilder.indexOf("{STORAGE_CACHE_SIZE}")) >= 0) {
                contentBuilder.replace(index, index + "{STORAGE_CACHE_SIZE}".length(), storageCacheSizeStr);
            }
            while ((index = contentBuilder.indexOf("{DB_COMPACT_THREADS}")) >= 0) {
                contentBuilder.replace(index, index + "{DB_COMPACT_THREADS}".length(), dbCompactThreadsStr);
            }
            while ((index = contentBuilder.indexOf("{DB_MAX_BYTES_FOR_LEVEL_BASE}")) >= 0) {
                contentBuilder.replace(index, index + "{DB_MAX_BYTES_FOR_LEVEL_BASE}".length(), dbMaxBytesForLevelBaseStr);
            }
            while ((index = contentBuilder.indexOf("{DB_TARGET_FILE_SIZE_BASE}")) >= 0) {
                contentBuilder.replace(index, index + "{DB_TARGET_FILE_SIZE_BASE}".length(), dbTargetFileSizeBaseStr);
            }
            while ((index = contentBuilder.indexOf("{GLOBAL_QPS}")) >= 0) {
                contentBuilder.replace(index, index + "{GLOBAL_QPS}".length(), globalQpsStr);
            }
            while ((index = contentBuilder.indexOf("{GLOBAL_IP_QPS}")) >= 0) {
                contentBuilder.replace(index, index + "{GLOBAL_IP_QPS}".length(), globalIpQpsStr);
            }
            
            // Handle SOLIDITY_NODE_PORT placeholder
            if (configSolidityNodePort != null) {
                while ((index = contentBuilder.indexOf("{SOLIDITY_NODE_PORT}")) >= 0) {
                    contentBuilder.replace(index, index + "{SOLIDITY_NODE_PORT}".length(), configSolidityNodePort);
                }
            } else {
                // If solidityNodePort is null, remove the entire solidityPort line to avoid leaving placeholder
                content = contentBuilder.toString();
                content = content.replaceAll("\\s+solidityPort\\s*=\\s*\\{SOLIDITY_NODE_PORT\\}\\s*\\r?\\n", "");
                contentBuilder = new StringBuilder(content);
            }
            
            // If placeholders were used, also update listen.port (which doesn't have a placeholder)
            if (usesPlaceholders) {
                content = contentBuilder.toString();
                content = content.replaceAll("listen\\.port = .*", "listen.port = " + configP2pPort);
                contentBuilder = new StringBuilder(content);
            }
            
            // Write config file efficiently with explicit charset
            Files.write(configPath, contentBuilder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            // Validate JAR file exists
            Path jarPath = Paths.get("/usr/local/tron/FullNode.jar");
            if (!Files.exists(jarPath)) {
                System.err.println("ERROR: FullNode.jar does not exist: /usr/local/tron/FullNode.jar");
                System.exit(1);
            }
            
            // Build and execute Java command based on network
            // Auto-detect optimal heap size from system memory (default behavior)
            // JAVA_HEAP_SIZE environment variable can override auto-detection if needed
            String heapSizeStr = getEnv("JAVA_HEAP_SIZE");
            // Initialize with network-specific default to ensure variable is always initialized
            // Optimized defaults for 50GB containers: 30GB for mainnet, 6GB for nile (reduced from 36GB/8GB)
            int heapSizeGB = (network == null || network.isEmpty() || "mainnet".equals(network)) ? 30 : 6;
            boolean heapSizeSet = false;
            
            // Check if JAVA_HEAP_SIZE is explicitly set (allows manual override)
            if (heapSizeStr != null && !heapSizeStr.isEmpty()) {
                try {
                    // Try parsing as integer first, then as double if that fails (handles "1.5" -> 2)
                    try {
                        heapSizeGB = Integer.parseInt(heapSizeStr);
                    } catch (NumberFormatException e) {
                        // If integer parse fails, try double and round
                        double heapSizeDouble = Double.parseDouble(heapSizeStr);
                        heapSizeGB = (int) Math.round(heapSizeDouble);
                        if (heapSizeGB <= 0) {
                            throw new NumberFormatException("Heap size must be positive");
                        }
                    }
                    System.out.println("Using JAVA_HEAP_SIZE from environment: " + heapSizeGB + "GB (override)");
                    heapSizeSet = true;
                } catch (NumberFormatException e) {
                    System.err.println("Invalid JAVA_HEAP_SIZE: " + heapSizeStr + ", auto-detecting from system memory");
                    heapSizeSet = false; // Force auto-detection
                }
            }
            
            // Auto-detect optimal heap size from system memory (default behavior)
            if (!heapSizeSet) {
                // Re-detect system memory if needed (in case it wasn't detected earlier)
                if (systemMemoryGB <= 0) {
                    systemMemoryGB = detectSystemMemoryGB();
                }
                if (systemMemoryGB > 0) {
                    int calculatedHeap = calculateOptimalHeapSize(systemMemoryGB, network);
                    if (calculatedHeap > 0) {
                        heapSizeGB = calculatedHeap;
                        System.out.println("Auto-detected optimal heap size: " + heapSizeGB + "GB (65% of " + systemMemoryGB + "GB system memory, optimized to prevent OOM)");
                    } else {
                        // Fall back to network-specific defaults (already set above)
                        System.out.println("Could not calculate optimal heap size, using network default: " + heapSizeGB + "GB");
                    }
                } else {
                    // Could not detect system memory, use network-specific defaults (already set above)
                    System.out.println("Could not detect system memory, using network default: " + heapSizeGB + "GB");
                    System.out.println("Note: Set JAVA_HEAP_SIZE environment variable to override if needed");
                }
            }
            
            // Calculate optimal GC threads based on CPU count
            // For G1GC: use CPU/4 for concurrent threads, CPU/2 for parallel threads
            int gcConcurrentThreads = Math.max(2, Math.min(cpuCount / 4, 16));
            int gcParallelThreads = Math.max(2, Math.min(cpuCount / 2, 32));
            
            // Calculate optimal code cache size based on CPU and heap
            // More CPUs and larger heaps benefit from larger code cache
            String codeCacheSize;
            if (heapSizeGB >= 48 && cpuCount >= 12) {
                codeCacheSize = "768m";
            } else if (heapSizeGB >= 32 || cpuCount >= 8) {
                codeCacheSize = "512m";
            } else {
                codeCacheSize = "256m";
            }
            
            // Calculate optimal metaspace size based on heap size
            // Optimized metaspace for better performance with larger heaps
            String metaspaceSize;
            String maxMetaspaceSize;
            if (heapSizeGB >= 36) {
                metaspaceSize = "512m";
                maxMetaspaceSize = "2G"; // Optimized for large heaps
            } else if (heapSizeGB >= 24) {
                metaspaceSize = "512m";
                maxMetaspaceSize = "1.5G";
            } else if (heapSizeGB >= 16) {
                metaspaceSize = "256m";
                maxMetaspaceSize = "1G";
            } else {
                metaspaceSize = "256m";
                maxMetaspaceSize = "512m";
            }
            
            // Calculate optimal direct memory size based on heap
            // Direct memory is used for NIO operations, optimized for better performance
            String maxDirectMemorySize;
            if (heapSizeGB >= 36) {
                maxDirectMemorySize = "4G"; // Optimized for large heaps
            } else if (heapSizeGB >= 24) {
                maxDirectMemorySize = "3G";
            } else if (heapSizeGB >= 16) {
                maxDirectMemorySize = "2G";
            } else {
                maxDirectMemorySize = "1G";
            }
            
            // Use G1GC for heaps >= 8GB (better than CMS for modern JVMs)
            String javaOptsCommon;
            if (heapSizeGB >= 8) {
                // Calculate optimal G1GC region size based on heap
                // Region size should be power of 2 between 1MB and 32MB
                // For large heaps, use larger regions to reduce overhead
                String g1RegionSize;
                if (heapSizeGB >= 64) {
                    g1RegionSize = "32m";
                } else if (heapSizeGB >= 32) {
                    g1RegionSize = "16m"; // Optimized for 32GB+ heaps
                } else if (heapSizeGB >= 16) {
                    g1RegionSize = "8m";
                } else {
                    g1RegionSize = "4m";
                }
                
                // Calculate optimal GC pause target based on heap size
                // Reduced pause targets to improve responsiveness and reduce CPU spikes
                int maxGCPauseMillis = heapSizeGB >= 32 ? 250 : (heapSizeGB >= 16 ? 150 : 100);
                
                // Calculate initiating heap occupancy percent
                // Start GC earlier to prevent memory pressure and reduce CPU spikes
                int initiatingHeapOccupancyPercent = heapSizeGB >= 32 ? 40 : (heapSizeGB >= 16 ? 45 : 50);
                
                // G1GC optimized for large heaps and multiple CPUs (Java 8 compatible)
                javaOptsCommon = String.format(
                    "-XX:ReservedCodeCacheSize=%s " +
                    "-XX:MetaspaceSize=%s " +
                    "-XX:MaxMetaspaceSize=%s " +
                    "-XX:MaxDirectMemorySize=%s " +
                    "-XX:+HeapDumpOnOutOfMemoryError " +
                    "-XX:HeapDumpPath=/data/heap_dump.hprof " +
                    "-XX:+PrintGCDetails " +
                    "-XX:+PrintGCDateStamps " +
                    "-XX:+PrintGCApplicationStoppedTime " +
                    "-Xloggc:/data/gc.log " +
                    "-XX:+UseG1GC " +
                    "-XX:MaxGCPauseMillis=%d " +
                    "-XX:G1HeapRegionSize=%s " +
                    "-XX:InitiatingHeapOccupancyPercent=%d " +
                    "-XX:ConcGCThreads=%d " +
                    "-XX:ParallelGCThreads=%d " +
                    "-XX:+ParallelRefProcEnabled " +
                    "-XX:+UseStringDeduplication " +
                    "-XX:+DisableExplicitGC",
                    codeCacheSize, metaspaceSize, maxMetaspaceSize, maxDirectMemorySize,
                    maxGCPauseMillis, g1RegionSize, initiatingHeapOccupancyPercent,
                    gcConcurrentThreads, gcParallelThreads
                );
            } else {
                // CMS for smaller heaps (< 8GB)
                javaOptsCommon = String.format(
                    "-XX:ReservedCodeCacheSize=%s " +
                    "-XX:MetaspaceSize=%s " +
                    "-XX:MaxMetaspaceSize=%s " +
                    "-XX:MaxDirectMemorySize=%s " +
                    "-XX:+HeapDumpOnOutOfMemoryError " +
                    "-XX:+PrintGCDetails " +
                    "-XX:+PrintGCDateStamps " +
                    "-XX:+UseConcMarkSweepGC " +
                    "-XX:NewRatio=2 " +
                    "-XX:+CMSScavengeBeforeRemark " +
                    "-XX:+ParallelRefProcEnabled " +
                    "-XX:+UseCMSInitiatingOccupancyOnly " +
                    "-XX:CMSInitiatingOccupancyFraction=70 " +
                    "-XX:ParallelGCThreads=%d",
                    codeCacheSize, metaspaceSize, maxMetaspaceSize, maxDirectMemorySize,
                    gcParallelThreads
                );
            }
            
            // Add NUMA support for large heaps on multi-socket systems
            // NUMA helps when heap > 32GB and CPU >= 16 cores
            String numaOpts = "";
            if (heapSizeGB >= 32 && cpuCount >= 16) {
                numaOpts = " -XX:+UseNUMA";
            } else {
                numaOpts = " -XX:-UseNUMA";
            }
            
            // For very large heaps, AlwaysPreTouch can take a long time or fail
            // Only enable it for heaps <= 32GB to avoid startup issues
            // For larger heaps, let OS handle memory allocation on demand
            String alwaysPreTouch = (heapSizeGB <= 32) ? " -XX:+AlwaysPreTouch" : " -XX:-AlwaysPreTouch";
            
            // Add JIT compiler optimizations for high-performance servers
            String compilerOpts = "";
            if (cpuCount >= 8) {
                // Tiered compilation with more aggressive optimizations for multi-core systems
                compilerOpts = " -XX:+TieredCompilation " +
                              "-XX:TieredStopAtLevel=4 " +
                              "-XX:CompileThreshold=10000";
            } else {
                compilerOpts = " -XX:+TieredCompilation";
            }
            
            // Compressed OOPs: Only enable for heaps <= 32GB
            // For heaps > 32GB, compressed OOPs are automatically disabled by JVM
            // Explicitly disable to avoid warnings
            if (heapSizeGB <= 32) {
                compilerOpts += " -XX:+UseCompressedOops -XX:+UseCompressedClassPointers";
            } else {
                // Explicitly disable compressed OOPs for large heaps to avoid warnings
                compilerOpts += " -XX:-UseCompressedOops -XX:-UseCompressedClassPointers";
            }
            
            // Add performance optimizations for large memory systems
            // Note: Large pages require system configuration and can cause shared memory errors
            // Only enable if system is properly configured (we'll disable by default to avoid errors)
            String performanceOpts = "";
            if (heapSizeGB >= 16) {
                // Enable aggressive optimizations for large heaps
                performanceOpts = " -XX:+AggressiveOpts";
                // Large pages are disabled by default to avoid shared memory reservation errors
                // To enable large pages, the system must be configured with:
                // echo 20000 > /proc/sys/vm/nr_hugepages
                // And proper permissions must be set
                // Uncomment the following lines if large pages are properly configured:
                // if (heapSizeGB >= 32) {
                //     performanceOpts += " -XX:+UseLargePages -XX:LargePageSizeInBytes=2m";
                // }
            }
            
            String javaOpts = javaOptsCommon + 
                String.format(" -Xms%dG -Xmx%dG", heapSizeGB, heapSizeGB) +
                alwaysPreTouch +
                numaOpts +
                compilerOpts +
                performanceOpts;
            
            // Build command efficiently - pre-allocate ArrayList with estimated capacity
            // Estimate: java + ~30 JVM options + jar + 4 args + optional flags = ~40
            List<String> command = new ArrayList<>(40);
            command.add("java");
            
            // Split javaOpts efficiently - avoid regex overhead by using manual parsing
            // This is faster than split("\\s+") for large option strings
            int start = 0;
            int len = javaOpts.length();
            for (int i = 0; i < len; i++) {
                char c = javaOpts.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (i > start) {
                        command.add(javaOpts.substring(start, i));
                    }
                    // Skip consecutive whitespace
                    while (i + 1 < len && Character.isWhitespace(javaOpts.charAt(i + 1))) {
                        i++;
                    }
                    start = i + 1;
                }
            }
            // Add last token if any
            if (start < len) {
                command.add(javaOpts.substring(start));
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
            if (heapSizeGB >= 8) {
                System.out.println("GC Configuration (G1GC):");
                System.out.println("  Concurrent GC threads: " + gcConcurrentThreads);
                System.out.println("  Parallel GC threads: " + gcParallelThreads);
            } else {
                System.out.println("GC Configuration (CMS):");
                System.out.println("  Parallel GC threads: " + gcParallelThreads);
            }
            System.out.println("JVM Optimizations:");
            System.out.println("  Code Cache: " + codeCacheSize);
            System.out.println("  Metaspace: " + metaspaceSize + " (max: " + maxMetaspaceSize + ")");
            System.out.println("  Direct Memory: " + maxDirectMemorySize);
            
            // Calculate total estimated memory usage
            // Helper function to parse memory size strings (handles decimals like "1.5G")
            int directMemGB = 0;
            try {
                if (maxDirectMemorySize.endsWith("G")) {
                    String value = maxDirectMemorySize.replace("G", "");
                    directMemGB = (int) Math.round(Double.parseDouble(value));
                } else if (maxDirectMemorySize.endsWith("m")) {
                    String value = maxDirectMemorySize.replace("m", "");
                    directMemGB = (int) Math.round(Double.parseDouble(value)) / 1024;
                }
            } catch (NumberFormatException e) {
                // If parsing fails, default to 0
                directMemGB = 0;
            }
            
            int metaspaceGB = 0;
            try {
                if (maxMetaspaceSize.endsWith("G")) {
                    String value = maxMetaspaceSize.replace("G", "");
                    metaspaceGB = (int) Math.round(Double.parseDouble(value));
                } else if (maxMetaspaceSize.endsWith("m")) {
                    String value = maxMetaspaceSize.replace("m", "");
                    metaspaceGB = (int) Math.round(Double.parseDouble(value)) / 1024;
                }
            } catch (NumberFormatException e) {
                // If parsing fails, default to 0
                metaspaceGB = 0;
            }
            int totalEstimatedGB = heapSizeGB + directMemGB + metaspaceGB;
            
            System.out.println("Memory Configuration Summary:");
            System.out.println("  Heap: " + heapSizeGB + "GB");
            System.out.println("  Direct Memory (max): " + maxDirectMemorySize);
            System.out.println("  Metaspace (max): " + maxMetaspaceSize);
            System.out.println("  Storage Cache: " + (storageCacheSize / 1024 / 1024) + "MB");
            System.out.println("  Estimated Total JVM Memory: ~" + totalEstimatedGB + "GB");
            
            // Warn if memory usage is high
            if (totalEstimatedGB > 20) {
                System.out.println("WARNING: Total estimated memory usage (" + totalEstimatedGB + "GB) is high.");
                System.out.println("Make sure your Docker container has enough memory allocated and other containers have sufficient RAM.");
            }
            
            // Check Docker memory limit and warn if too low
            try {
                Path dockerMemLimit = Paths.get("/sys/fs/cgroup/memory/memory.limit_in_bytes");
                if (Files.exists(dockerMemLimit)) {
                    String memLimitStr = new String(Files.readAllBytes(dockerMemLimit)).trim();
                    try {
                        long memLimitBytes = Long.parseLong(memLimitStr);
                        if (memLimitBytes < Long.MAX_VALUE) {
                            long memLimitGB = memLimitBytes / 1024 / 1024 / 1024;
                            System.out.println("Docker container memory limit: " + memLimitGB + "GB");
                            if (memLimitGB < totalEstimatedGB + 4) {
                                System.err.println("WARNING: Docker memory limit (" + memLimitGB + "GB) may be too low for estimated usage (" + totalEstimatedGB + "GB).");
                                System.err.println("Recommended: Set Docker memory limit to at least " + (totalEstimatedGB + 4) + "GB to allow for OS and other processes.");
                                System.err.println("Example: docker run --memory=" + (totalEstimatedGB + 4) + "g ...");
                            }
                        }
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            } catch (Exception e) {
                // Ignore - may not be available
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
            
            // Use size-limited buffers to prevent EntryPoint OOM errors
            // Only keep last 1MB of output and 512KB of errors for error reporting
            // Since we're already streaming to stdout/stderr, we don't need full history
            final int MAX_OUTPUT_BUFFER_SIZE = 1024 * 1024; // 1MB
            final int MAX_ERROR_BUFFER_SIZE = 512 * 1024;   // 512KB
            final java.util.List<String> outputLines = new java.util.ArrayList<>(1000);
            final java.util.List<String> errorLines = new java.util.ArrayList<>(500);
            final int[] outputBufferSize = {0};
            final int[] errorBufferSize = {0};
            final boolean[] processEnded = {false};
            
            // Stream stdout in real-time with size-limited buffering
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8), 8192)) {
                    String line;
                    while ((line = reader.readLine()) != null || !processEnded[0]) {
                        if (line != null) {
                            System.out.println(line);
                            System.out.flush();
                            // Only buffer for error reporting (size-limited circular buffer)
                            try {
                                int lineSize = line.length() + 1; // +1 for newline
                                synchronized (outputLines) {
                                    outputLines.add(line);
                                    outputBufferSize[0] += lineSize;
                                    // Remove oldest lines if buffer exceeds limit
                                    while (outputBufferSize[0] > MAX_OUTPUT_BUFFER_SIZE && !outputLines.isEmpty()) {
                                        String removed = outputLines.remove(0);
                                        outputBufferSize[0] -= (removed.length() + 1);
                                    }
                                }
                            } catch (OutOfMemoryError e) {
                                // If buffer OOM, clear it and continue streaming
                                System.err.println("WARNING: EntryPoint output buffer OOM, clearing buffer. Output will still be streamed.");
                                synchronized (outputLines) {
                                    outputLines.clear();
                                    outputBufferSize[0] = 0;
                                }
                            }
                        } else {
                            // No more lines, but process might still be running
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                } catch (OutOfMemoryError e) {
                    // If we still get OOM, disable buffering completely
                    System.err.println("WARNING: EntryPoint buffer OOM, disabling output buffering. Output will still be streamed.");
                    synchronized (outputLines) {
                        outputLines.clear();
                        outputBufferSize[0] = 0;
                    }
                } catch (IOException e) {
                    if (!processEnded[0]) {
                        System.err.println("Error reading process stdout: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
            outputThread.setDaemon(true); // Don't prevent JVM shutdown
            outputThread.start();
            
            // Stream stderr in real-time with size-limited buffering
            Thread errorThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8), 4096)) {
                    String line;
                    while ((line = reader.readLine()) != null || !processEnded[0]) {
                        if (line != null) {
                            System.err.println("[stderr] " + line);
                            System.err.flush();
                            // Only buffer for error reporting (size-limited circular buffer)
                            try {
                                int lineSize = line.length() + 1; // +1 for newline
                                synchronized (errorLines) {
                                    errorLines.add(line);
                                    errorBufferSize[0] += lineSize;
                                    // Remove oldest lines if buffer exceeds limit
                                    while (errorBufferSize[0] > MAX_ERROR_BUFFER_SIZE && !errorLines.isEmpty()) {
                                        String removed = errorLines.remove(0);
                                        errorBufferSize[0] -= (removed.length() + 1);
                                    }
                                }
                            } catch (OutOfMemoryError e) {
                                // If buffer OOM, clear it and continue streaming
                                System.err.println("WARNING: EntryPoint error buffer OOM, clearing buffer. Errors will still be streamed.");
                                synchronized (errorLines) {
                                    errorLines.clear();
                                    errorBufferSize[0] = 0;
                                }
                            }
                        } else {
                            // No more lines, but process might still be running
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                } catch (OutOfMemoryError e) {
                    // If we still get OOM, disable buffering completely
                    System.err.println("WARNING: EntryPoint buffer OOM, disabling error buffering. Errors will still be streamed.");
                    synchronized (errorLines) {
                        errorLines.clear();
                        errorBufferSize[0] = 0;
                    }
                } catch (IOException e) {
                    if (!processEnded[0]) {
                        System.err.println("Error reading process stderr: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
            errorThread.setDaemon(true); // Don't prevent JVM shutdown
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
            if (exitedImmediately && errorLines.isEmpty()) {
                try {
                    // Try reading raw bytes from error stream
                    InputStream errorStream = process.getErrorStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead = errorStream.read(buffer);
                    if (bytesRead > 0) {
                        String errorText = new String(buffer, 0, bytesRead);
                        System.err.println("[stderr-raw] " + errorText);
                        synchronized (errorLines) {
                            errorLines.add(errorText);
                        }
                    }
                } catch (Exception e) {
                    // Ignore - stream may already be closed
                }
            }
            
            if (exitCode != 0) {
                System.err.println("\n=== Process exited with code: " + exitCode + " ===");
                synchronized (errorLines) {
                    if (!errorLines.isEmpty()) {
                        System.err.println("\n=== Error output (stderr) - last " + errorLines.size() + " lines ===");
                        for (String line : errorLines) {
                            System.err.println(line);
                        }
                        System.err.println("=== End of error output ===");
                    }
                }
                synchronized (outputLines) {
                    if (!outputLines.isEmpty()) {
                        System.err.println("\n=== Standard output (stdout) - last " + outputLines.size() + " lines ===");
                        for (String line : outputLines) {
                            System.err.println(line);
                        }
                        System.err.println("=== End of standard output ===");
                    }
                }
                if (outputLines.isEmpty() && errorLines.isEmpty()) {
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
}
