package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Worker node: Tracks instance state, current work, and temporal metrics via ring buffer.
 */
public class WorkerNode {
    private final String instanceId;
    private final String ip;
    private final AtomicInteger work;
    private final AtomicInteger missedPings;
    private final int maxCapacity;
    private final AtomicReference<Double> cpuUtilization;

    private static final int HISTORY_SIZE = 12;
    private final double[] cpuHistory;
    private final int[] workHistory;
    private int historyIndex = 0;
    private int historySamples = 0;
    public static final int DEFAULT_MAX_CAPACITY = 1000000;

    public WorkerNode(String instanceId, String ip) {
        this(instanceId, ip, DEFAULT_MAX_CAPACITY); //TODO: Tune this default capacity
    }

    public WorkerNode(String instanceId, String ip, int maxCapacity) {
        this.instanceId = instanceId;
        this.ip = ip;
        this.work = new AtomicInteger(0);
        this.missedPings = new AtomicInteger(0);
        this.maxCapacity = maxCapacity;
        this.cpuUtilization = new AtomicReference<>(0.0);
        this.cpuHistory = new double[HISTORY_SIZE];
        this.workHistory = new int[HISTORY_SIZE];
        
        for (int i = 0; i < HISTORY_SIZE; i++) {
            this.cpuHistory[i] = 0.0;
            this.workHistory[i] = 0;
        }
    }

    public String getInstanceId() { return instanceId; }
    public String getIp() { return ip; }
    public AtomicInteger getWork() { return work; }
    public int getMissedPings() { return missedPings.get(); }
    public void setMissedPings(int value) { missedPings.set(value); }
    public void incrementMissedPings() { missedPings.incrementAndGet(); }
    public void incrementWork(int value) { work.addAndGet(value); }
    public double getCpuUtilization() { return cpuUtilization.get(); }
    public double getRelativeWork() { return (double) work.get() / maxCapacity; }
    public int getMaxCapacity() { return maxCapacity; }
    
    // Update current CPU and record both CPU and work to history (synchronized snapshot)
    public void setCpuUtilization(double value) {
        double clamped = Math.max(0.0, Math.min(100.0, value));
        cpuUtilization.set(clamped);
        recordMetrics(clamped, work.get());
    }
    
    // Maintain circular ring buffer with atomic index advance
    private synchronized void recordMetrics(double cpuValue, int workValue) {
        cpuHistory[historyIndex] = cpuValue;
        workHistory[historyIndex] = workValue;
        
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
        if (historySamples < HISTORY_SIZE) historySamples++;
    }
    
    public synchronized void clearHistory() {
        for (int i = 0; i < HISTORY_SIZE; i++) {
            cpuHistory[i] = 0.0;
            workHistory[i] = 0;
        }
        historyIndex = 0;
        historySamples = 0;
    }
    public synchronized int getHistorySampleCount() { return historySamples; }
    
    // Calculate mean across all samples in history
    public synchronized double getAverageCpuUtilization() {
        if (historySamples == 0) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < historySamples; i++) sum += cpuHistory[i];
        return sum / historySamples;
    }
    
    // Calculate mean work across all samples
    public synchronized double getAverageWork() {
        if (historySamples == 0) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < historySamples; i++) sum += workHistory[i];
        return sum / historySamples;
    }
    
    // Return average work normalized to capacity (0.0 to 1.0+)
    public synchronized double getAverageRelativeWork() {
        return getAverageWork() / maxCapacity;
    }
    
    public synchronized double getMaxCpuInHistory() {
        double max = 0.0;
        for (int i = 0; i < historySamples; i++) if (cpuHistory[i] > max) max = cpuHistory[i];
        return max;
    }
    
    public synchronized double getMaxWorkInHistory() {
        int max = 0;
        for (int i = 0; i < historySamples; i++) if (workHistory[i] > max) max = workHistory[i];
        return max;
    }

}
