package pt.ulisboa.tecnico.cnv.webserver;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Singleton CPU monitor: Collects samples every 500ms, maintains 90-second moving average.
 */
public class CPUMonitor {
    private static final Logger LOGGER = Logger.getLogger(CPUMonitor.class.getName());
    private static final CPUMonitor INSTANCE = new CPUMonitor();
    private static final int WINDOW_SECONDS = 90;
    private static final int SAMPLE_INTERVAL_MS = 500;
    private static final int SAMPLES_PER_WINDOW = (WINDOW_SECONDS * 1000) / SAMPLE_INTERVAL_MS;
    
    private final OperatingSystemMXBean osBean;
    private final ConcurrentLinkedQueue<Double> cpuSamples;
    private final ScheduledExecutorService samplerThread;
    private volatile double currentAverage = 0.0;
    
    private CPUMonitor() {
        this.osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.cpuSamples = new ConcurrentLinkedQueue<>();
        this.samplerThread = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "CPUMonitor-Sampler");
            t.setDaemon(true);
            return t;
        });
        samplerThread.scheduleAtFixedRate(this::collectSample, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    public static CPUMonitor getInstance() {
        return INSTANCE;
    }
    
    // Collect sample and maintain sliding window
    private void collectSample() {
        try {
            double cpuLoad = osBean.getProcessCpuLoad();
            if (cpuLoad >= 0) {
                cpuSamples.offer(cpuLoad);
                while (cpuSamples.size() > SAMPLES_PER_WINDOW) {
                    cpuSamples.poll();
                }
                
                // Calculate moving average
                double sum = 0.0;
                for (Double sample : cpuSamples) {
                    sum += sample;
                }
                currentAverage = cpuSamples.isEmpty() ? 0.0 : sum / cpuSamples.size();
            }
        } catch (Exception e) {
            LOGGER.warning("[CPUMonitor] Error collecting CPU sample: " + e.getMessage());
        }
    }
    
    // Moving average over 90-second window
    public double getCpuUtilization() {
        return currentAverage;
    }
    
    public double getCpuUtilizationPercent() { return getCpuUtilization() * 100.0; }
    
    public int getSampleCount() { return cpuSamples.size(); }
    
    public void shutdown() {
        samplerThread.shutdown();
        try {
            if (!samplerThread.awaitTermination(5, TimeUnit.SECONDS)) samplerThread.shutdownNow();
        } catch (InterruptedException e) {
            samplerThread.shutdownNow();
        }
    }
}
